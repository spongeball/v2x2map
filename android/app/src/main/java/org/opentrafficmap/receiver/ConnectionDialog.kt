package org.opentrafficmap.receiver

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog

/** Picker shown when the status pill is tapped while disconnected: USB or BT. */
object ConnectionDialog {

    enum class Choice { USB, BT }

    fun show(context: Context, onChoice: (Choice) -> Unit): Dialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_connection, null)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.conn_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        view.findViewById<android.view.View>(R.id.rowUsb).setOnClickListener {
            onChoice(Choice.USB); dialog.dismiss()
        }
        view.findViewById<android.view.View>(R.id.rowBt).setOnClickListener {
            onChoice(Choice.BT); dialog.dismiss()
        }
        dialog.show()
        return dialog
    }
}
