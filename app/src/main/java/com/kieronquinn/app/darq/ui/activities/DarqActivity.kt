package com.kieronquinn.app.darq.ui.activities

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.view.menu.ListMenuItemView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.kieronquinn.app.darq.R
import com.kieronquinn.monetcompat.app.MonetCompatActivity
import com.kieronquinn.monetcompat.extensions.views.applyMonetRecursively

class DarqActivity: MonetCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setUseLightStatusNav(false)
        lifecycleScope.launchWhenResumed {
            monet.awaitMonetReady()
            setContentView(R.layout.activity_darq)
            val lightStatusNav = resources.getBoolean(R.bool.lightStatusNav)
            setUseLightStatusNav(lightStatusNav)
        }
    }

    // MonetCompat 0.4.1 registers this receiver without an export flag.
    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter,
        broadcastPermission: String?,
        scheduler: Handler?
    ): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            filter.countActions() == 1 && filter.hasAction("android.intent.action.OVERLAY_CHANGED")
        ) {
            return super.registerReceiver(
                receiver, filter, broadcastPermission, scheduler, Context.RECEIVER_NOT_EXPORTED
            )
        }
        return super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
    }

    /**
     *  Applies Monet to the overflow menu's checkbox
     */
    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet
    ): View? {
        if (applyBackgroundColorToMenu && name == ListMenuItemView::class.java.name) {
            //Toolbar dropdown menu list item
            (parent?.parent as? View)?.run {
                post {
                    applyMonetRecursively()
                }
            }
        }
        return super.onCreateView(parent, name, context, attrs)
    }

    private fun setUseLightStatusNav(enabled: Boolean){
        WindowCompat.getInsetsController(window, window.decorView)?.run {
            isAppearanceLightNavigationBars = enabled
            isAppearanceLightStatusBars = enabled
        }
    }

}