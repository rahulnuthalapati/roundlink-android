/*
 * SPDX-FileCopyrightText: 2025 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package com.roundlink.ui.list

import android.view.LayoutInflater
import android.view.View
import com.roundlink.Device
import com.roundlink.R

class PairingDeviceItem(
    device: Device,
    callback: ((d: Device) -> Unit)
) : DeviceItem(device, callback) {

    override fun toString(): String {
        return "PairingDeviceItem(device=$device)"
    }

    override fun inflateView(layoutInflater: LayoutInflater): View {
        return super.inflateView(layoutInflater).also {
            if (device.compareProtocolVersion() > 0) {
                binding.listItemEntrySummary.setText(R.string.protocol_version_newer)
                binding.listItemEntrySummary.visibility = View.VISIBLE
            }
        }
    }
}
