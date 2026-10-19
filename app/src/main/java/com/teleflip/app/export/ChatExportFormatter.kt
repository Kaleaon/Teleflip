package com.teleflip.app.export

import org.json.JSONArray
import org.json.JSONObject

object ChatExportFormatter {

    fun formatToJson(
        chatId: Long,
        chatTitle: String,
        messages: List<ExportMessage>,
        exportedAt: String,
    ): String {
        val root = JSONObject()
        root.put("chat_id", chatId)
        root.put("chat_title", chatTitle)
        root.put("exported_at", exportedAt)
        root.put("message_count", messages.size)

        val jsonArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("id", msg.messageId)
            msgObj.put("timestamp", msg.timestamp)
            msgObj.put("date", msg.dateFormatted)
            msgObj.put("sender_id", msg.senderId)
            msgObj.put("sender_name", msg.senderName)
            msgObj.put("is_outbound", msg.isOutbound)
            msgObj.put("text", msg.text)

            if (msg.attachmentType != null) {
                msgObj.put("attachment_type", msg.attachmentType)
            } else {
                msgObj.put("attachment_type", JSONObject.NULL)
            }

            if (msg.attachmentDetails != null) {
                msgObj.put("attachment_details", msg.attachmentDetails)
            } else {
                msgObj.put("attachment_details", JSONObject.NULL)
            }

            jsonArray.put(msgObj)
        }

        root.put("messages", jsonArray)
        return root.toString(2)
    }

    fun formatToHtml(
        chatId: Long,
        chatTitle: String,
        messages: List<ExportMessage>,
        exportedAt: String,
    ): String {
        val sb = StringBuilder()
        val safeTitle = escapeHtml(chatTitle)

        sb.append("<!DOCTYPE html>\n")
        sb.append("<html lang=\"en\">\n")
        sb.append("<head>\n")
        sb.append("  <meta charset=\"UTF-8\">\n")
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
        sb.append("  <title>Chat Export - ").append(safeTitle).append("</title>\n")
        sb.append("  <style>\n")
        sb.append("    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #12161D; color: #FFFFFF; margin: 0; padding: 20px; line-height: 1.5; }\n")
        sb.append("    .export-header { background-color: #1E2633; border: 1px solid #2B3648; border-radius: 10px; padding: 16px 20px; margin-bottom: 24px; }\n")
        sb.append("    .export-header h1 { margin: 0 0 8px 0; font-size: 1.4rem; color: #00D2FF; }\n")
        sb.append("    .export-header p { margin: 4px 0; font-size: 0.9rem; color: #8A9BAE; }\n")
        sb.append("    .messages-container { display: flex; flex-direction: column; gap: 12px; max-width: 800px; margin: 0 auto; }\n")
        sb.append("    .message-bubble { max-width: 75%; padding: 12px 16px; border-radius: 12px; font-size: 0.95rem; word-wrap: break-word; position: relative; box-shadow: 0 1px 3px rgba(0,0,0,0.3); }\n")
        sb.append("    .message-bubble.inbound { background-color: #1E2633; border: 1px solid #2B3648; align-self: flex-start; color: #FFFFFF; }\n")
        sb.append("    .message-bubble.outbound { background-color: #24A1DE; align-self: flex-end; color: #FFFFFF; }\n")
        sb.append("    .sender-name { font-weight: bold; font-size: 0.85rem; margin-bottom: 4px; color: #00D2FF; }\n")
        sb.append("    .outbound .sender-name { color: #E0F2FE; }\n")
        sb.append("    .message-text { margin-bottom: 6px; white-space: pre-wrap; }\n")
        sb.append("    .attachment-badge { background-color: rgba(0, 0, 0, 0.25); border-left: 3px solid #FFC837; padding: 6px 10px; margin: 6px 0; border-radius: 4px; font-size: 0.85rem; color: #FFC837; }\n")
        sb.append("    .timestamp { font-size: 0.75rem; color: #8A9BAE; text-align: right; margin-top: 4px; }\n")
        sb.append("    .outbound .timestamp { color: #D0E8FF; }\n")
        sb.append("  </style>\n")
        sb.append("</head>\n")
        sb.append("<body>\n")

        sb.append("  <div class=\"export-header\">\n")
        sb.append("    <h1>Chat Export: ").append(safeTitle).append("</h1>\n")
        sb.append("    <p><strong>Chat ID:</strong> ").append(chatId).append("</p>\n")
        sb.append("    <p><strong>Exported At:</strong> ").append(escapeHtml(exportedAt)).append("</p>\n")
        sb.append("    <p><strong>Total Messages:</strong> ").append(messages.size).append("</p>\n")
        sb.append("  </div>\n\n")

        sb.append("  <div class=\"messages-container\">\n")
        for (msg in messages) {
            val directionClass = if (msg.isOutbound) "outbound" else "inbound"
            val safeSender = escapeHtml(msg.senderName)
            val safeText = escapeHtml(msg.text).replace("\n", "<br>")
            val safeDate = escapeHtml(msg.dateFormatted)

            sb.append("    <div class=\"message-bubble ").append(directionClass).append("\">\n")
            sb.append("      <div class=\"sender-name\">").append(safeSender).append("</div>\n")

            if (safeText.isNotBlank()) {
                sb.append("      <div class=\"message-text\">").append(safeText).append("</div>\n")
            }

            if (!msg.attachmentType.isNullOrBlank()) {
                val detailsStr = if (!msg.attachmentDetails.isNullOrBlank()) ": ${escapeHtml(msg.attachmentDetails)}" else ""
                sb.append("      <div class=\"attachment-badge\">📎 [")
                    .append(escapeHtml(msg.attachmentType))
                    .append("]")
                    .append(detailsStr)
                    .append("</div>\n")
            }

            sb.append("      <div class=\"timestamp\">").append(safeDate).append("</div>\n")
            sb.append("    </div>\n")
        }
        sb.append("  </div>\n")

        sb.append("</body>\n")
        sb.append("</html>\n")

        return sb.toString()
    }

    fun escapeHtml(text: String): String {
        if (text.isEmpty()) return ""
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
