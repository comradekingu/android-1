/*
 * Copyright © 2013 – 2016 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter.ui.setup

import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.loader.app.LoaderManager
import androidx.loader.content.AsyncTaskLoader
import androidx.loader.content.Loader
import com.etesync.syncadapter.R
import com.etesync.syncadapter.log.Logger
import com.etesync.syncadapter.ui.DebugInfoActivity
import com.etesync.syncadapter.ui.setup.BaseConfigurationFinder.Configuration

class DetectConfigurationFragment : DialogFragment(), LoaderManager.LoaderCallbacks<Configuration> {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val progress = ProgressDialog(activity)
        progress.setTitle(R.string.setting_up_encryption)
        progress.setMessage(getString(R.string.setting_up_encryption_content))
        progress.isIndeterminate = true
        progress.setCanceledOnTouchOutside(false)
        isCancelable = false
        return progress
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loaderManager.initLoader(0, arguments, this)
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<Configuration> {
        return ServerConfigurationLoader(requireContext(), args!!.getParcelable(ARG_LOGIN_CREDENTIALS) as LoginCredentials)
    }

    override fun onLoadFinished(loader: Loader<Configuration>, data: Configuration?) {
        if (data != null) {
            if (data.isFailed) {
                // no service found: show error message
                requireFragmentManager().beginTransaction()
                        .add(NothingDetectedFragment.newInstance(data.error!!.localizedMessage), null)
                        .commitAllowingStateLoss()
            } else if (data.isLegacy) {
                // legacy service found: continue
                requireFragmentManager().beginTransaction()
                        .replace(android.R.id.content, EncryptionDetailsFragment.newInstance(data))
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
            } else {
                requireFragmentManager().beginTransaction()
                        .replace(android.R.id.content, CreateAccountFragment.newInstance(data))
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
            }
        } else
            Logger.log.severe("Configuration detection failed")

        dismissAllowingStateLoss()
    }

    override fun onLoaderReset(loader: Loader<Configuration>) {}


    class NothingDetectedFragment : DialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(activity!!)
                    .setTitle(R.string.setting_up_encryption)
                    .setIcon(R.drawable.ic_error_dark)
                    .setMessage(requireArguments().getString(KEY_LOGS))
                    .setPositiveButton(android.R.string.ok) { dialog, which ->
                        // dismiss
                    }
                    .create()
        }

        companion object {
            private val KEY_LOGS = "logs"

            fun newInstance(logs: String): NothingDetectedFragment {
                val args = Bundle()
                args.putString(KEY_LOGS, logs)
                val fragment = NothingDetectedFragment()
                fragment.arguments = args
                return fragment
            }
        }
    }

    internal class ServerConfigurationLoader(context: Context, val credentials: LoginCredentials) : AsyncTaskLoader<Configuration>(context) {

        override fun onStartLoading() {
            forceLoad()
        }

        override fun loadInBackground(): Configuration? {
            return BaseConfigurationFinder(context, credentials).findInitialConfiguration()
        }
    }

    companion object {
        protected val ARG_LOGIN_CREDENTIALS = "credentials"

        fun newInstance(credentials: LoginCredentials): DetectConfigurationFragment {
            val frag = DetectConfigurationFragment()
            val args = Bundle(1)
            args.putParcelable(ARG_LOGIN_CREDENTIALS, credentials)
            frag.arguments = args
            return frag
        }
    }
}
