/***********************
Yau Mun Lim, University College London, 14 Jul 2022
Script tested to be working on QuPath v0.3.2.

Adapted from Mike Nelson's post (https://forum.image.sc/t/qupath-multiple-image-alignment-and-object-transfer/35521/2) to work on transformation matrices created from the alignment of multiple target slides onto reference slides in a single QuPath project.

This script assumes WSI filenames are in the format: 
    slideID<sep>stain.fileExt
    or
    slideID<sep>tissueBlock<sep>stain.fileExt

If you have annotations within annotations, you may get duplicates. Ask on the forum or change the def pathObjects line.

It will use ALL of the affine transforms in the Affine folder to transform the objects in the reference image to the target images
that are named in the Affine folder. 

Requires creating each affine transformation from the target images so that there are multiple transform files with different names.
***********************/
 
// SET ME! Delete existing objects
def deleteExisting = false

// SET ME! Change this if things end up in the wrong place
def createInverse = false

// Specify reference stain
String fileNameSep = "_" // Separator used in image name

import qupath.lib.objects.PathCellObject
import qupath.lib.objects.PathDetectionObject
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.objects.PathTileObject
import qupath.lib.roi.RoiTools
import qupath.lib.roi.interfaces.ROI

import java.awt.geom.AffineTransform

import static qupath.lib.gui.scripting.QPEx.*

// Affine folder path
def path = buildFilePath(PROJECT_BASE_DIR, 'Affine')

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
    logger.error('Other than 1 source/reference stain detected, please check image name')
    return
}

// Read and obtain filenames from Affine folder
new File(path).eachFile{ f->
    f.withObjectInputStream {
        matrix = it.readObject()

        def targetFileName = f.getName()
        def (targetImageName, imageExt) = targetFileName.split('\\.')
        // def (slideID, tissueBlock, targetStain) = targetImageName.split(fileNameSep)
        def splitName = targetImageName.split(fileNameSep)

        def targetImage = targetProjectImageList.find {it.getImageName() == targetFileName}
        if (!targetImage) {
            logger.error('Could not find image with name ' + f.getName())
            return
        }
        def targetImageData = targetImage.readImageData()
        def targetHierarchy = targetImageData.getHierarchy()

        def refFileName
        if (splitName.size() == 2) {
            refFileName = splitName[0] + fileNameSep + refStain + "." + imageExt
        } else if (splitName.size() == 3) {
            refFileName = splitName[0] + fileNameSep + splitName[1] + fileNameSep + refStain + "." + imageExt
        }

        def refImage = sourceProjectImageList.find {it.getImageName() == refFileName}
        if (!refImage) {
            logger.error('Could not find image with name ' + refFileName)
            return
        }
        def refImageData = refImage.readImageData()
        def refHierarchy = refImageData.getHierarchy()

        def pathObjects = refHierarchy.getAnnotationObjects()

        print 'Aligning objects from reference slide ' + refFileName + ' onto target slide ' + targetFileName

        // Define the transformation matrix
        def transform = new AffineTransform(
                matrix[0], matrix[3], matrix[1],
                matrix[4], matrix[2], matrix[5]
        )
        if (createInverse)
            transform = transform.createInverse()
            
        if (deleteExisting)
            targetHierarchy.clearAll()
            
        def newObjects = []
        for (pathObject in pathObjects) {
            newObjects << transformObject(pathObject, transform)
        }
        targetHierarchy.addPathObjects(newObjects)
        targetImage.saveImageData(targetImageData)
    }
}
print 'Done!'

java.awt.Toolkit.defaultToolkit.beep()
Thread.sleep(1000)
java.awt.Toolkit.defaultToolkit.beep()

/**
 * Transform object, recursively transforming all child objects
 *
 * @param pathObject
 * @param transform
 * @return
 */
PathObject transformObject(PathObject pathObject, AffineTransform transform) {
    // Create a new object with the converted ROI
    def roi = pathObject.getROI()
    def roi2 = transformROI(roi, transform)
    def newObject = null
    if (pathObject instanceof PathCellObject) {
        def nucleusROI = pathObject.getNucleusROI()
        if (nucleusROI == null)
            newObject = PathObjects.createCellObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
        else
            newObject = PathObjects.createCellObject(roi2, transformROI(nucleusROI, transform), pathObject.getPathClass(), pathObject.getMeasurementList())
    } else if (pathObject instanceof PathTileObject) {
        newObject = PathObjects.createTileObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
    } else if (pathObject instanceof PathDetectionObject) {
        newObject = PathObjects.createDetectionObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
    } else {
        newObject = PathObjects.createAnnotationObject(roi2, pathObject.getPathClass(), pathObject.getMeasurementList())
    }
    // Handle child objects
    if (pathObject.hasChildren()) {
        newObject.addPathObjects(pathObject.getChildObjects().collect({transformObject(it, transform)}))
    }
    return newObject
}

/**
 * Transform ROI (via conversion to Java AWT shape)
 *
 * @param roi
 * @param transform
 * @return
 */
ROI transformROI(ROI roi, AffineTransform transform) {
    def shape = RoiTools.getShape(roi) // Should be able to use roi.getShape() - but there's currently a bug in it for rectangles/ellipses!
    shape2 = transform.createTransformedShape(shape)
    return RoiTools.getShapeROI(shape2, roi.getImagePlane(), 0.5)
}