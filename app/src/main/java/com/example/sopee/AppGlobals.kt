package com.example.allinoneflushapp

import android.app.Application

object AppGlobals {
    lateinit var applicationContext: Application
    var accessibilityService: AccessibilityAutomationService? = null
}
