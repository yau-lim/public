/***********************
Yau Mun Lim, University College London, 14 Jul 2022
Script tested to be working on QuPath v0.3.2.

Adapted from:
    Sara McArdle's post (https://forum.image.sc/t/calling-image-alignment-function-from-a-script/35617) to be able to create transformation matrices from aligning target slides to a reference slide stain (refStain) in a single QuPath project containing multiple sets of serial sections.

    Mark Zaidi's adaptation of my adaption... (https://github.com/MarkZaidi/QuPath-Image-Alignment/blob/main/Calculate-Transforms.groovy)

This script assumes WSI filenames are in the format: 
    slideID<sep>stain.fileExt
    or
    slideID<sep>tissueBlock<sep>stain.fileExt

All transformation matrices will be created as aligned to the reference image for every other non-reference (target) images in the project, and stored in the Affine folder.

Performs annotation-based alignment if annotations are present, otherwise uses intensity-based alignment.

Output stored to the Affine folder within the Project folder.
***********************/

// INPUTS
//////////////////////////////////
String registrationType="AFFINE"
String alignMethod = "IMAGE" // "ANNO" for annotation-based or "IMAGE" for intensity-based alignment
String fileNameSep = "_" // Separator used in image name
def AutoAlignPixelSize = 40 // downsample factor for calculating transform. Does not affect scaling of output image
use_single_channel = 0 // Use a single channel from each image for alignment (set to channel number to use). Set to 0 to use all channels.
/////////////////////////////////

import javafx.scene.transform.Affine
import qupath.lib.gui.scripting.QPEx
import qupath.lib.images.servers.ImageServer

import java.awt.Graphics2D
import java.awt.Transparency
import java.awt.color.ColorSpace
import java.awt.image.BufferedImage

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.TermCriteria;
import org.bytedeco.opencv.global.opencv_video;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacpp.indexer.Indexer;

import qupath.lib.gui.dialogs.Dialogs;
import qupath.lib.images.servers.PixelCalibration;

import qupath.lib.regions.RegionRequest;
import qupath.opencv.tools.OpenCVTools

import java.awt.image.ComponentColorModel
import java.awt.image.DataBuffer

import static qupath.lib.gui.scripting.QPEx.*;

// Get list of all images in target project
def targetProjectImageList = getProject().getImageList()

// Load source project
File sourceProjFile = Dialogs.promptForFile(
    "Select QuPath project file containing source images",
    new File(buildFilePath(PROJECT_BASE_DIR)),
    "QuPath project file",
    ProjectIO.DEFAULT_PROJECT_EXTENSION
    )
var sourceProject = ProjectIO.loadProject(sourceProjFile, BufferedImage.class)
def sourceProjectImageList = sourceProject.getImageList()

// Get source/reference stain from image name
def refStain = []
for (def sourceEntry in sourceProjectImageList) {
    def sourceName = sourceEntry.getImageName()
    def sourceImageName = sourceName.split('\\.')[0]
    def sourceSplitName = sourceImageName.split(fileNameSep)
    if (sourceSplitName.size() == 2) {
        refStain << sourceSplitName[1]
    } else if (sourceSplitName.size() == 3) {
        refStain << sourceSplitName[2]
    } else {
        logger.error('"' + sourceImageName + '"' + ' name is not compatible, please check image name')
        return
    }
}

// Check that there is only one source/reference stain
refStain = refStain.unique()
if (refStain.size() == 1) {
    refStain = refStain[0]
} else {
    logger.warn('Other than 1 source/reference stain detected, please check image name')
    return
}

// Create empty lists
def imageNameList = []
def slideIDList = []
def stainList = []
def missingList = []

// Split image file names to desired variables and add to previously created lists
def imageExt = []
for (def targetEntry in targetProjectImageList) {
    def targetName = targetEntry.getImageName()
    // def (imageName, imageExt) = name.split('\\.')
    def targetImageName = targetName.split('\\.')[0]
    imageExt << targetName.split('\\.')[1]
    imageNameList << targetImageName
    def splitName = targetImageName.split(fileNameSep)
    if (splitName.size() == 2) {
        slideIDList << splitName[0]
        stainList << splitName[1]
    } else if (splitName.size() == 3) {
        slideIDList << splitName[0] + fileNameSep + splitName[1]
        stainList << splitName[2]
    } else {
        logger.error('"' + targetImageName + '"' + ' name is not compatible, please check image name')
        return
    }
}

// Get single image extension, which also excludes images with appended string after ext
imageExt = imageExt.unique()
def extRemove = []
while (imageExt.size() > 1) {
    for (def ext in imageExt) {
        if (ext.split(' ').size() > 1) {
            extRemove << ext
        }
    }
    imageExt.removeAll(extRemove)
}
imageExt = imageExt[0]


// Remove duplicate entries from lists
slideIDList = slideIDList.unique()
stainList = stainList.unique()
// if (stainList.size() == 1) {
//     print 'Only one stain detected. Target slides may not be loaded.'
//     return
// }

// Create Affine folder to put transformation matrix files
path = buildFilePath(PROJECT_BASE_DIR, 'Affine')
mkdirs(path)

// Process all combinations of slide IDs, tissue blocks, and stains based on reference stain slide onto target slides
for (slide in slideIDList) {
    for (stain in stainList) {
        if (stain != refStain) {
            def refFileName = slide + fileNameSep + refStain + "." + imageExt
            def targetFileName = slide + fileNameSep + stain + "." + imageExt
            path = buildFilePath(PROJECT_BASE_DIR, 'Affine', targetFileName)
            def refImage = sourceProjectImageList.find {it.getImageName() == refFileName}
            def targetImage = targetProjectImageList.find {it.getImageName() == targetFileName}
            if (!refImage) {
                logger.warn('Source/reference slide ' + refFileName + ' missing!')
                missingList << refFileName
                continue
            }
            if (!targetImage) {
                logger.warn('Target slide ' + targetFileName + ' missing!')
                missingList << targetFileName
                continue
            }
            println("Aligning source/reference " + refFileName + " to target " + targetFileName)
            ImageServer<BufferedImage> serverBase = refImage.readImageData().getServer()
            ImageServer<BufferedImage> serverOverlay = targetImage.readImageData().getServer()

            def serverBasePrep = refImage.readImageData()
            def serverOverlayPrep = targetImage.readImageData()

            Affine affine=[]

            // Perform the alignment at the the pixel size specified by AutoAlignPixelSize. If annotations present, use annotation-based alignment. Otherwise, use intensity-based alignment.
            if (alignMethod == "ANNO") {
                if(serverBasePrep.hierarchy.nObjects() < 1 || serverOverlayPrep.hierarchy.nObjects() < 1) {
                    println "Source/reference image has " + serverBasePrep.hierarchy.nObjects() + " annotations"
                    println "Target image has " + serverOverlayPrep.hierarchy.nObjects() + " annotations"
                    return
                } else {
                    autoAlignPrep(AutoAlignPixelSize,"AREA",serverBasePrep,serverOverlayPrep,affine,registrationType,use_single_channel)
                }                
            } else if (alignMethod == "IMAGE") {
                // print 'No annotations found, aligning based on intensity.'
                autoAlignPrep(AutoAlignPixelSize,"notAREA",serverBasePrep,serverOverlayPrep,affine,registrationType,use_single_channel)
            }

            // // Test a range and find optimal resolution
            // autoAlign(serverBase,serverOverlay,registrationType,affine,50)
            // autoAlign(serverBase,serverOverlay,registrationType,affine,20)
            // autoAlign(serverBase,serverOverlay,registrationType,affine,10)
            // autoAlign(serverBase,serverOverlay,registrationType,affine,5)

            def matrix = []
            matrix << affine.getMxx()
            matrix << affine.getMxy()
            matrix << affine.getTx()
            matrix << affine.getMyx()
            matrix << affine.getMyy()
            matrix << affine.getTy()

            new File(path).withObjectOutputStream {
                it.writeObject(matrix)
            }
        }
    }
}

if (missingList.isEmpty() == true) {
    println 'Done!'
} else {
    missingList = missingList.unique()
    println 'Done! Missing slides: ' + missingList
}

// Notification when script is complete
java.awt.Toolkit.defaultToolkit.beep()
Thread.sleep(1000)
java.awt.Toolkit.defaultToolkit.beep()


// Functions

//creates an image server using the actual images (for intensity-based alignment) or a labeled image server (for annotation-based).
double autoAlignPrep(double requestedPixelSizeMicrons, String alignmentMethod, ImageData<BufferedImage> imageDataBase, ImageData<BufferedImage> imageDataSelected, Affine affine, String registrationType, int use_single_channel) throws IOException {
    ImageServer<BufferedImage> serverBase, serverSelected;

    if (alignmentMethod == 'AREA') {
        logger.debug("Image alignment using area annotations");
        Map<PathClass, Integer> labels = new LinkedHashMap<>();
        int label = 1;
        labels.put(PathClassFactory.getPathClassUnclassified(), label++);
        for (def annotation : imageDataBase.getHierarchy().getAnnotationObjects()) {
            def pathClass = annotation.getPathClass();
            if (pathClass != null && !labels.containsKey(pathClass))
                labels.put(pathClass, label++);
        }
        for (def annotation : imageDataSelected.getHierarchy().getAnnotationObjects()) {
            def pathClass = annotation.getPathClass();
            if (pathClass != null && !labels.containsKey(pathClass))
                labels.put(pathClass, label++);
        }

        double downsampleBase = requestedPixelSizeMicrons / imageDataBase.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();
        serverBase = new LabeledImageServer.Builder(imageDataBase)
                .backgroundLabel(0)
                .addLabels(labels)
                .downsample(downsampleBase)
                .build();

        double downsampleSelected = requestedPixelSizeMicrons / imageDataSelected.getServer().getPixelCalibration().getAveragedPixelSize().doubleValue();
        serverSelected = new LabeledImageServer.Builder(imageDataSelected)
                .backgroundLabel(0)
                .addLabels(labels)
                .downsample(downsampleSelected)
                .build();
        // Disable single channel alignment when working with annotation-based alignment, may cause bugs
        use_single_channel=0
    } else {
        // Default - just use intensities
        logger.debug("Image alignment using intensities");
        serverBase = imageDataBase.getServer();
        serverSelected = imageDataSelected.getServer();
    }

    scaleFactor=autoAlign(serverBase, serverSelected, registrationType, affine, requestedPixelSizeMicrons, use_single_channel);
    return scaleFactor
}

double autoAlign(ImageServer<BufferedImage> serverBase, ImageServer<BufferedImage> serverOverlay, String regionstrationType, Affine affine, double requestedPixelSizeMicrons, use_single_channel) {
    PixelCalibration calBase = serverBase.getPixelCalibration();
    double pixelSizeBase = calBase.getAveragedPixelSizeMicrons();
    double downsampleBase = 1;
    if (!Double.isFinite(pixelSizeBase)) {
        // while (serverBase.getWidth() / downsampleBase > 2000)
        //     downsampleBase++;
        // logger.warn("Pixel size is unavailable! Default downsample value of {} will be used", downsampleBase);
        pixelSizeBase = 50
        downsampleBase = requestedPixelSizeMicrons / pixelSizeBase
    } else {
        downsampleBase = requestedPixelSizeMicrons / pixelSizeBase
    }

    PixelCalibration calOverlay = serverOverlay.getPixelCalibration()
    double pixelSizeOverlay = calOverlay.getAveragedPixelSizeMicrons()
    double downsampleOverlay = 1
    if (!Double.isFinite(pixelSizeOverlay)) {
        // while (serverBase.getWidth() / downsampleOverlay > 2000)
        //    downsampleOverlay++;
        // logger.warn("Pixel size is unavailable! Default downsample value of {} will be used", downsampleOverlay)
        pixelSizeOverlay=50
        downsampleOverlay = requestedPixelSizeMicrons / pixelSizeOverlay
    } else {
        downsampleOverlay = requestedPixelSizeMicrons / pixelSizeOverlay
    }

    double scaleFactor = downsampleBase / downsampleOverlay

    BufferedImage imgBase = serverBase.readBufferedImage(RegionRequest.createInstance(serverBase.getPath(), downsampleBase, 0, 0, serverBase.getWidth(), serverBase.getHeight()));
    BufferedImage imgOverlay = serverOverlay.readBufferedImage(RegionRequest.createInstance(serverOverlay.getPath(), downsampleOverlay, 0, 0, serverOverlay.getWidth(), serverOverlay.getHeight()));


    Mat matBase
    Mat matOverlay

    if (use_single_channel==0) {
        imgBase = ensureGrayScale(imgBase)
        imgOverlay = ensureGrayScale(imgOverlay)
        matBase = OpenCVTools.imageToMat(imgBase)
        matOverlay = OpenCVTools.imageToMat(imgOverlay)
    } else {
        matBase = OpenCVTools.imageToMat(imgBase)
        matOverlay = OpenCVTools.imageToMat(imgOverlay)
        int channel = use_single_channel-1
        //print ('using channel ' + channel)
        matBase = OpenCVTools.splitChannels(matBase)[channel]
        matOverlay = OpenCVTools.splitChannels(matOverlay)[channel]
        //use this to preview how the channel looks
        //OpenCVTools.matToImagePlus('Channel:' + channel.toString(), matBase).show()
    }

    Mat matTransform = Mat.eye(2, 3, opencv_core.CV_32F).asMat();
// Initialize using existing transform
//		affine.setToTransform(mxx, mxy, tx, myx, myy, ty);
    try {
        FloatIndexer indexer = matTransform.createIndexer()
        indexer.put(0, 0, (float)affine.getMxx());
        indexer.put(0, 1, (float)affine.getMxy());
        indexer.put(0, 2, (float)(affine.getTx() / downsampleBase));
        indexer.put(1, 0, (float)affine.getMyx());
        indexer.put(1, 1, (float)affine.getMyy());
        indexer.put(1, 2, (float)(affine.getTy() / downsampleBase));
//			System.err.println(indexer);
    } catch (Exception e) {
        logger.error("Error closing indexer", e);
    }


//		// Might want to mask out completely black pixels (could indicate missing data)?
//		def matMask = new opencv_core.Mat(matOverlay.size(), opencv_core.CV_8UC1, Scalar.ZERO);
    TermCriteria termCrit = new TermCriteria(TermCriteria.COUNT, 100, 0.0001);
//		OpenCVTools.matToImagePlus(matBase, "Base").show();
//		OpenCVTools.matToImagePlus(matOverlay, "Overlay").show();
////
//		Mat matTemp = new Mat();
//		opencv_imgproc.warpAffine(matOverlay, matTemp, matTransform, matBase.size());
//		OpenCVTools.matToImagePlus(matTemp, "Transformed").show();
    try {
        int motion;
        switch (regionstrationType) {
            case "AFFINE":
                motion = opencv_video.MOTION_AFFINE;
                break;
            case "RIGID":
                motion = opencv_video.MOTION_EUCLIDEAN;
                break;
            default:
                logger.warn("Unknown registraton type {} - will use {}", regionstrationType, RegistrationType.AFFINE);
                motion = opencv_video.MOTION_AFFINE;
                break;
        }
        double result = opencv_video.findTransformECC(matBase, matOverlay, matTransform, motion, termCrit, null);
        logger.info("Transformation result: {}", result);
    } catch (Exception e) {
        // Dialogs.showErrorNotification("Estimate transform", "Unable to estimated transform - result did not converge");
        logger.error("Unable to estimated transform - result did not converge");
        logger.error("Unable to estimate transform", e);
        // return;
    }

// To use the following function, images need to be the same size
//		def matTransform = opencv_video.estimateRigidTransform(matBase, matOverlay, false);
    Indexer indexer = matTransform.createIndexer();
    affine.setToTransform(
            indexer.getDouble(0, 0),
            indexer.getDouble(0, 1),
            indexer.getDouble(0, 2) * downsampleBase,
            indexer.getDouble(1, 0),
            indexer.getDouble(1, 1),
            indexer.getDouble(1, 2) * downsampleBase
    );
    indexer.release();

//		matMask.release();
    matBase.release();
    matOverlay.release();
    matTransform.release();

    return scaleFactor
}

// Subfunctions

static BufferedImage ensureGrayScale(BufferedImage img) {
    if (img.getType() == BufferedImage.TYPE_BYTE_GRAY)
        return img;
    if (img.getType() == BufferedImage.TYPE_BYTE_INDEXED) {
        ColorSpace cs = ColorSpace.getInstance(ColorSpace.CS_GRAY);
        def colorModel = new ComponentColorModel(cs, 8 as int[], false, true,
                Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
        return new BufferedImage(colorModel, img.getRaster(), false, null);
    }
    BufferedImage imgGray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
    Graphics2D g2d = imgGray.createGraphics();
    g2d.drawImage(img, 0, 0, null);
    g2d.dispose();
    return imgGray;
}