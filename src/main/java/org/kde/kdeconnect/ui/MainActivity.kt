/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.GravityCompat
import androidx.core.view.get
import androidx.core.view.size
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.apache.commons.lang3.ArrayUtils
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.share.ShareSettingsFragment
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityMainBinding

private const val MENU_ENTRY_ADD_DEVICE = 1 //0 means no-selection
private const val MENU_ENTRY_SETTINGS = 2
private const val MENU_ENTRY_DEVICE_FIRST_ID = 1000 //All subsequent ids are devices in the menu
private const val MENU_ENTRY_DEVICE_UNKNOWN = 9999 //It's still a device, but we don't know which one yet
private const val STORAGE_LOCATION_CONFIGURED = 2020
private const val STATE_SELECTED_MENU_ENTRY = "selected_entry" //Saved only in onSaveInstanceState
private const val STATE_SELECTED_DEVICE = "selected_device" //Saved persistently in preferences

class MainActivity : AppCompatActivity(), OnSharedPreferenceChangeListener {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mBottomNav: BottomNavigationView by lazy { binding.bottomNavigation!! }


    private var mCurrentDevice: String? = null
    private var mCurrentMenuEntry = 0
        set(value) {
            field = value
            //Enabling "go to default fragment on back" callback when user in settings
            mainFragmentCallback.isEnabled = value == MENU_ENTRY_SETTINGS
        }
    private val preferences: SharedPreferences by lazy { getSharedPreferences("stored_menu_selection", MODE_PRIVATE) }



    private val mainFragmentCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            mCurrentDevice?.let {
                onDeviceSelected(null)
            } ?: run {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceHelper.initializeDeviceId(this)

        val root = binding.root
        setContentView(root)
        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // Note: The preference changed listener should be registered before getting the name, because getting
        // it can trigger a background fetch from the internet that will eventually update the preference
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)

        mBottomNav.setOnItemSelectedListener { menuItem: MenuItem ->
            when (menuItem.itemId) {
                R.id.nav_devices -> {
                    mCurrentMenuEntry = MENU_ENTRY_ADD_DEVICE // Default to devices tab
                    val deviceToShow = mCurrentDevice ?: findFirstAvailableDevice()
                    if (deviceToShow != null) {
                        mCurrentDevice = deviceToShow // Ensure it's set if we found one
                        setContentFragment(DeviceFragment.newInstance(deviceToShow, false))
                        mainFragmentCallback.isEnabled = true
                    } else {
                        setContentFragment(PairingFragment())
                        mainFragmentCallback.isEnabled = false
                    }
                }

                R.id.nav_pair -> {
                    mCurrentMenuEntry = MENU_ENTRY_ADD_DEVICE
                    setContentFragment(PairingFragment())
                    mainFragmentCallback.isEnabled = false
                }

                R.id.nav_settings -> {
                    mCurrentMenuEntry = MENU_ENTRY_SETTINGS
                    setContentFragment(SettingsFragment())
                }

            }
            true
        }

        // Decide which menu entry should be selected at start
        var savedDevice: String?
        var savedMenuEntry: Int
        when {
            intent.hasExtra(FLAG_FORCE_OVERVIEW) -> {
                Log.i(this::class.simpleName, "Requested to start main overview")
                savedDevice = null
                savedMenuEntry = MENU_ENTRY_ADD_DEVICE
            }

            intent.hasExtra(EXTRA_DEVICE_ID) -> {
                Log.i(this::class.simpleName, "Loading selected device from parameter")
                savedDevice = intent.getStringExtra(EXTRA_DEVICE_ID)
                savedMenuEntry = MENU_ENTRY_DEVICE_UNKNOWN
                // If pairStatus is not empty, then the user has accepted/reject the pairing from the notification
                val pairStatus = intent.getStringExtra(PAIR_REQUEST_STATUS)
                if (pairStatus != null) {
                    Log.i(this::class.simpleName, "Pair status is $pairStatus")
                    savedDevice = onPairResultFromNotification(savedDevice, pairStatus)
                    if (savedDevice == null) {
                        savedMenuEntry = MENU_ENTRY_ADD_DEVICE
                    }
                }
            }

            savedInstanceState != null -> {
                Log.i(this::class.simpleName, "Loading selected device from saved activity state")
                savedDevice = savedInstanceState.getString(STATE_SELECTED_DEVICE)
                savedMenuEntry = savedInstanceState.getInt(STATE_SELECTED_MENU_ENTRY, MENU_ENTRY_ADD_DEVICE)
            }

            else -> {
                Log.i(this::class.simpleName, "Loading selected device from persistent storage")
                savedDevice = preferences.getString(STATE_SELECTED_DEVICE, null)
                savedMenuEntry = if (savedDevice != null) MENU_ENTRY_DEVICE_UNKNOWN else MENU_ENTRY_ADD_DEVICE
            }
        }
        mCurrentMenuEntry = savedMenuEntry
        mCurrentDevice = savedDevice
        // Update BottomNav selection
        mBottomNav.selectedItemId = when (mCurrentMenuEntry) {
            MENU_ENTRY_SETTINGS -> R.id.nav_settings
            MENU_ENTRY_ADD_DEVICE -> R.id.nav_pair
            else -> R.id.nav_devices
        }

        //FragmentManager will restore whatever fragment was there
        if (savedInstanceState != null) {
            val frag = supportFragmentManager.findFragmentById(R.id.container)
            if (frag !is DeviceFragment || frag.deviceId == savedDevice) return
        }

        // Activate the chosen fragment and select the entry in the menu
        if (savedMenuEntry >= MENU_ENTRY_DEVICE_FIRST_ID && savedDevice != null) {
            onDeviceSelected(savedDevice)
        } else {
            when (mCurrentMenuEntry) {
                MENU_ENTRY_SETTINGS -> setContentFragment(SettingsFragment())
                else -> setContentFragment(PairingFragment())
            }
        }

        val missingPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionResult = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (permissionResult != PackageManager.PERMISSION_GRANTED) {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS)) {
                    missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        if(missingPermissions.isNotEmpty()){
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), RESULT_NOTIFICATIONS_ENABLED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun onPairResultFromNotification(deviceId: String?, pairStatus: String): String? {
        assert(deviceId != null)
        if (pairStatus != PAIRING_PENDING) {
            val device = KdeConnect.getInstance().getDevice(deviceId)
            if (device == null) {
                Log.w(this::class.simpleName, "Reject pairing - device no longer exists: $deviceId")
                return null
            }
            when (pairStatus) {
                PAIRING_ACCEPTED -> device.acceptPairing()
                PAIRING_REJECTED -> device.cancelPairing()
            }
        }
        return if (pairStatus == PAIRING_ACCEPTED || pairStatus == PAIRING_PENDING) deviceId else null
    }



    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }



    override fun onStart() {
        super.onStart()
        BackgroundService.Start(applicationContext)
        onBackPressedDispatcher.addCallback(mainFragmentCallback)
    }

    override fun onStop() {
        mainFragmentCallback.remove()
        super.onStop()
    }

    @JvmOverloads
    fun onDeviceSelected(deviceId: String?, fromDeviceList: Boolean = false) {
        mCurrentDevice = deviceId
        preferences.edit { putString(STATE_SELECTED_DEVICE, deviceId) }
        
        if (mBottomNav.selectedItemId != R.id.nav_devices) {
            mBottomNav.selectedItemId = R.id.nav_devices
            // setSelectedItemId triggers the listener, which will show the fragment.
        } else {
            // Already in Devices tab, listener won't be triggered, update UI manually.
            if (mCurrentDevice != null) {
                setContentFragment(DeviceFragment.newInstance(mCurrentDevice!!, fromDeviceList))
                mainFragmentCallback.isEnabled = true
            } else {
                setContentFragment(PairingFragment())
                mainFragmentCallback.isEnabled = false
            }
        }
    }

    private fun setContentFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    private fun findFirstAvailableDevice(): String? {
        return KdeConnect.getInstance().devices.values
            .firstOrNull { it.isReachable && it.isPaired }?.deviceId
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_DEVICE, mCurrentDevice)
        outState.putInt(STATE_SELECTED_MENU_ENTRY, mCurrentMenuEntry)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode == RESULT_NEEDS_RELOAD -> {
                CoroutineScope(Dispatchers.IO).launch {
                    KdeConnect.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
                }
            }
            requestCode == STORAGE_LOCATION_CONFIGURED && resultCode == RESULT_OK && data != null -> {
                val uri = data.data
                ShareSettingsFragment.saveStorageLocationPreference(this, uri)
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    fun isPermissionGranted(permissions: Array<String>, grantResults: IntArray, permission : String) : Boolean {
        val index = ArrayUtils.indexOf(permissions, permission)
        return index != ArrayUtils.INDEX_NOT_FOUND && grantResults[index] == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val permissionsGranted = ArrayUtils.contains(grantResults, PackageManager.PERMISSION_GRANTED)
        if (permissionsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isPermissionGranted(permissions, grantResults, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                // To get a writeable path manually on Android 10 and later for Share and Receive Plugin.
                // Otherwise, Receiving files will keep failing until the user chooses a path manually to receive files.
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                startActivityForResult(intent, STORAGE_LOCATION_CONFIGURED)
            }

            if (isPermissionGranted(permissions, grantResults, Manifest.permission.BLUETOOTH_CONNECT) &&
                isPermissionGranted(permissions, grantResults, Manifest.permission.BLUETOOTH_SCAN)) {
                PreferenceManager.getDefaultSharedPreferences(this).edit {
                    putBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, true)
                }
                setContentFragment(SettingsFragment())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isPermissionGranted(permissions, grantResults, Manifest.permission.POST_NOTIFICATIONS)) {
                // If PairingFragment is active, reload it
                if (mCurrentDevice == null) {
                    setContentFragment(PairingFragment())
                }
            }

            //New permission granted, reload plugins
            CoroutineScope(Dispatchers.IO).launch {
                KdeConnect.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (DeviceHelper.KEY_DEVICE_NAME_PREFERENCE == key) {
            BackgroundService.ForceRefreshConnections(this) //Re-send our identity packet
        }
    }



    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val PAIR_REQUEST_STATUS = "pair_req_status"
        const val PAIRING_ACCEPTED = "accepted"
        const val PAIRING_REJECTED = "rejected"
        const val PAIRING_PENDING = "pending"
        const val RESULT_NEEDS_RELOAD = RESULT_FIRST_USER
        const val RESULT_NOTIFICATIONS_ENABLED = RESULT_FIRST_USER+1
        const val FLAG_FORCE_OVERVIEW = "forceOverview"
    }


}
