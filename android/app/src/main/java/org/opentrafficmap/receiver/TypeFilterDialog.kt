package org.opentrafficmap.receiver

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Per-message-type checklist, shared by the MQTT and Range filter pickers. */
object TypeFilterDialog {

    fun show(context: Context, titleRes: Int, current: Set<String>, onSave: (Set<String>) -> Unit) {
        val allTypes = ItsG5Decoder.MsgType.values()
        val checked = allTypes.map { it.name in current }.toBooleanArray()
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMultiChoiceItems(allTypes.map { typeLabel(context, it) }.toTypedArray(), checked) { _, i, on ->
                checked[i] = on
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onSave(allTypes.filterIndexed { i, _ -> checked[i] }.map { it.name }.toSet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun typeLabel(ctx: Context, type: ItsG5Decoder.MsgType): String = ctx.getString(when (type) {
        ItsG5Decoder.MsgType.UNKNOWN -> R.string.msgtype_unknown
        ItsG5Decoder.MsgType.CAM     -> R.string.msgtype_cam
        ItsG5Decoder.MsgType.DENM    -> R.string.msgtype_denm
        ItsG5Decoder.MsgType.MAPEM   -> R.string.msgtype_mapem
        ItsG5Decoder.MsgType.SPATEM  -> R.string.msgtype_spatem
        ItsG5Decoder.MsgType.IVIM    -> R.string.msgtype_ivim
        ItsG5Decoder.MsgType.SREM    -> R.string.msgtype_srem
        ItsG5Decoder.MsgType.SSEM    -> R.string.msgtype_ssem
        ItsG5Decoder.MsgType.TLM     -> R.string.msgtype_tlm
        ItsG5Decoder.MsgType.RTCMEM  -> R.string.msgtype_rtcmem
    })
}
