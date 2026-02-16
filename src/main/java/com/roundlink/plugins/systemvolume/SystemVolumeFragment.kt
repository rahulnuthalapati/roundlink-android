/*
 * SPDX-FileCopyrightText: 2018 Nicolas Fella <nicolas.fella@gmx.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package com.roundlink.plugins.systemvolume

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import com.roundlink.helpers.calculateNewVolume
import com.roundlink.KdeConnect
import com.roundlink.plugins.mpris.MprisPlugin
import com.roundlink.plugins.mpris.VolumeKeyListener
import com.roundlink.plugins.systemvolume.SystemVolumePlugin.SinkListener
import com.roundlink.base.BaseFragment
import com.roundlink.extensions.setupBottomPadding
import com.roundlink.R
import com.roundlink.databinding.ListItemSystemvolumeBinding
import com.roundlink.databinding.SystemVolumeFragmentBinding

class SystemVolumeFragment : BaseFragment<SystemVolumeFragmentBinding>(),
    Sink.UpdateListener, SinkListener, VolumeKeyListener {

    private lateinit var plugin: SystemVolumePlugin
    private lateinit var recyclerAdapter: RecyclerSinkAdapter
    private var tracking = false

    private val deviceId: String?
        get() = arguments?.getString(MprisPlugin.DEVICE_ID_KEY)

    override fun getActionBarTitle() = getString(R.string.open_mpris_controls)

    override fun onInflateBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): SystemVolumeFragmentBinding {
        return SystemVolumeFragmentBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerAdapter = RecyclerSinkAdapter()
        binding.audioDevicesRecycler.apply {
            layoutManager = LinearLayoutManager(requireContext())
            addItemDecoration(ItemGapDecoration(resources.getDimensionPixelSize(R.dimen.activity_vertical_margin)))
            adapter = recyclerAdapter
            setupBottomPadding()
        }
        connectToPlugin(deviceId)
    }

    override fun onDestroyView() {
        disconnectFromPlugin(deviceId)
        super.onDestroyView()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun updateSink(sink: Sink) {
        // Don't set progress while the slider is moved
        if (!tracking) {
            requireActivity().runOnUiThread {
                if (::recyclerAdapter.isInitialized) {
                    recyclerAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun connectToPlugin(deviceId: String?) {
        val plugin = KdeConnect.getInstance().getDevicePlugin(
            deviceId,
            SystemVolumePlugin::class.java
        )
        if (plugin == null) {
            return
        }
        this.plugin = plugin
        plugin.addSinkListener(this@SystemVolumeFragment)
        sinksChanged()
    }

    private fun disconnectFromPlugin(deviceId: String?) {
        val plugin = KdeConnect.getInstance().getDevicePlugin(
            deviceId,
            SystemVolumePlugin::class.java
        )
        if (plugin == null) {
            return
        }
        plugin.removeSinkListener(this@SystemVolumeFragment)
    }

    override fun sinksChanged() {
        if (!::plugin.isInitialized || !::recyclerAdapter.isInitialized) {
            return
        }
        for (sink in plugin.sinks) {
            sink.addListener(this@SystemVolumeFragment)
        }
        requireActivity().runOnUiThread {
            val newSinks: List<Sink> = ArrayList(plugin.sinks)
            recyclerAdapter.submitList(newSinks)
        }
    }

    override fun onVolumeUp() {
        updateDefaultSinkVolume(5)
    }

    override fun onVolumeDown() {
        updateDefaultSinkVolume(-5)
    }

    private fun updateDefaultSinkVolume(percent: Int) {
        if (!::plugin.isInitialized) {
            return
        }

        val defaultSink = getDefaultSink(plugin) ?: return

        val newVolume = calculateNewVolume(
            defaultSink.volume,
            defaultSink.maxVolume,
            percent
        )

        if (defaultSink.volume == newVolume) return

        plugin.sendVolume(defaultSink.name, newVolume)
    }

    private inner class RecyclerSinkAdapter : ListAdapter<Sink?, SinkItemHolder>(SinkItemCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SinkItemHolder {
            val viewBinding = ListItemSystemvolumeBinding.inflate(layoutInflater, parent, false)
            return SinkItemHolder(viewBinding, plugin) { isUserTracking -> tracking = isUserTracking }
        }

        override fun onBindViewHolder(holder: SinkItemHolder, position: Int) {
            val sink = getItem(position)
            sink?.let { holder.bind(it) }
        }
    }

    companion object {
        fun newInstance(deviceId: String?): SystemVolumeFragment {
            val systemVolumeFragment = SystemVolumeFragment()

            val arguments = Bundle()
            arguments.putString(MprisPlugin.DEVICE_ID_KEY, deviceId)

            systemVolumeFragment.arguments = arguments

            return systemVolumeFragment
        }
    }
}
