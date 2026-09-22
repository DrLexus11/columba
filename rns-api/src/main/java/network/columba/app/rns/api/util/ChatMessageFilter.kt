package network.columba.app.rns.api.util

import network.columba.app.rns.api.model.ReceivedMessage
import org.json.JSONObject

/**
 * Whether an inbound LXMessage should appear as a chat bubble in the
 * conversation UI.
 *
 * Returns true when the message carries any user-visible payload:
 *   - non-blank text content
 *   - an image       (`fields[0x06]` = [FIELD_IMAGE])
 *   - file attachments (`fields[0x05]` = [LxmfFields.FIELD_FILE_ATTACHMENTS])
 *   - audio          (`fields[0x07]` = [LxmfFields.FIELD_AUDIO])
 *
 * Returns false otherwise. Side-channel-only frames — telemetry-only
 * location shares (FIELD_TELEMETRY / FIELD_TELEMETRY_STREAM /
 * FIELD_CUSTOM_META), reaction-only events (FIELD_REACTION),
 * icon-only chatter from Sideband / MeshChat — all fall through to
 * false and are routed via their dedicated flows
 * (`RnsTelemetry.locationTelemetryFlow`, `_reactionReceivedFlow`,
 * etc.) instead of rendering as empty bubbles.
 *
 * **Lives in `:rns-api` so both backends share one implementation.**
 * Previously the Kotlin backend (NativeRnsBackendImpl) had a
 * `handleIncomingTelemetry → isLocationOnlyMessage` check before
 * `_messages.tryEmit(...)`, but the Python backend
 * (PythonEventBridge.handleLxmfDelivery) emitted unconditionally —
 * so every telemetry-only / reaction-only LXMessage landed in the
 * DB as an empty row and the UI rendered an empty bubble. Centring
 * the predicate here means adding a new user-visible field is one
 * edit, not two, and there can never be drift between the backends.
 */
fun ReceivedMessage.isUserVisibleChatMessage(): Boolean {
    if (content.isNotBlank()) return true
    val json = fieldsJson?.takeIf { it.isNotEmpty() } ?: return false
    return try {
        val parsed = JSONObject(json)
        parsed.has(LxmfFields.FIELD_IMAGE.toString()) ||
            parsed.has(LxmfFields.FIELD_FILE_ATTACHMENTS.toString()) ||
            parsed.has(LxmfFields.FIELD_AUDIO.toString())
    } catch (_: Exception) {
        // Malformed fieldsJson with blank content — safer to drop
        // than render an empty bubble. The backend logs the parse
        // failure separately.
        false
    }
}

/**
 * Whether an inbound LXMessage carries an application payload.
 *
 * True when `fields[0xFB]` ([LxmfFields.FIELD_CUSTOM_TYPE]) is present --
 * upstream LXMF's own marker for "this is for an application". Such a message
 * is emitted to observers **even with no visible content**, because an
 * application frame need not carry any: a TAK drawing fragment is a few hundred
 * bytes of payload and not one character of text.
 *
 * Before this, the emission gate was [isUserVisibleChatMessage] alone, and every
 * content-less application message was dropped inside the backend before any
 * application could see it. Measured on the bench 2026-09-21: every fragment of
 * a drawing was proved delivered at the LXMF layer and not one reached TAK.
 *
 * **Consumers that render chat must still ask [isUserVisibleChatMessage]**
 * before making a row, or an application frame becomes an empty bubble -- the
 * exact failure the chat predicate above was written to stop.
 */
fun ReceivedMessage.carriesApplicationPayload(): Boolean {
    val json = fieldsJson?.takeIf { it.isNotEmpty() } ?: return false
    return try {
        JSONObject(json).has(LxmfFields.FIELD_CUSTOM_TYPE.toString())
    } catch (_: Exception) {
        false
    }
}
