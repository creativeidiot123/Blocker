package com.ankit.blocker.generics

import android.app.Service
import android.os.Binder
import com.ankit.blocker.utils.Logger

/**
 * Generic service binder that provides safe service connections and automatic rebinding capabilities.
 * This class helps manage the lifecycle of bound services and ensures proper cleanup.
 */
class ServiceBinder(private val service: Service) : Binder() {

    companion object {
        private const val TAG = "Blocker.ServiceBinder"

        // Actions for service binding and starting
        const val ACTION_BIND_TO_BLOCKER = "com.ankit.blocker.action.BIND_TO_BLOCKER"
        const val ACTION_START_BLOCKER_SERVICE = "com.ankit.blocker.action.START_BLOCKER_SERVICE"
    }

    /**
     * Gets the service instance that this binder is bound to.
     *
     * @return The service instance
     */
    fun getService(): Service {
        Logger.d(TAG, "Service instance requested: ${service.javaClass.simpleName}")
        return service
    }

}