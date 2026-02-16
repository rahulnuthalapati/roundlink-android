/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package com.roundlink.plugins.findremotedevice

import com.roundlink.NetworkPacket
import com.roundlink.plugins.findmyphone.FindMyPhonePlugin
import com.roundlink.plugins.Plugin
import com.roundlink.plugins.PluginFactory.LoadablePlugin
import com.roundlink.R

@LoadablePlugin
class FindRemoteDevicePlugin : Plugin() {
    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_findremotedevice)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_findremotedevice_desc)

    override fun onPacketReceived(np: NetworkPacket): Boolean = true

    override fun getUiMenuEntries(): List<PluginUiMenuEntry> = listOf(
        PluginUiMenuEntry(context.getString(R.string.ring)) { parentActivity ->
            device.sendPacket(NetworkPacket(FindMyPhonePlugin.PACKET_TYPE_FINDMYPHONE_REQUEST))
        }
    )

    override val supportedPacketTypes: Array<String> = emptyArray()

    override val outgoingPacketTypes: Array<String> = arrayOf(FindMyPhonePlugin.PACKET_TYPE_FINDMYPHONE_REQUEST)
}
