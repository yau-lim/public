String title = "Title"
String content = "Content"

def webhookUrl = new URL("https://example.webhook.office.com/webhookb2/webhook-link")

String json = """{
    "summary": "$title",
    "sections": [{
        "activityTitle": "$title",
        "activitySubtitle": "$content"
    }]
}"""

def connection = webhookUrl.openConnection()
connection.setRequestMethod("POST")
connection.setDoOutput(true)
connection.setRequestProperty("Content-Type", "application/json")
connection.getOutputStream().write(json.getBytes("UTF-8"))

def postRC = connection.getResponseCode()
if (postRC == 200) {
    logger.info("Notification sent to MS Teams")
} else {
    logger.error("POST failed with error $postRC")
}