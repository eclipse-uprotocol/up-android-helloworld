/*
 * Copyright (c) 2024 General Motors GTO LLC
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * SPDX-FileType: SOURCE
 * SPDX-FileCopyrightText: 2024 General Motors GTO LLC
 * SPDX-License-Identifier: Apache-2.0
 */

package org.eclipse.uprotocol.uphelloworld.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.graphics.Color
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.google.type.TimeOfDay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.covesa.uservice.example.hello_world.v1.HelloRequest
import org.covesa.uservice.example.hello_world.v1.HelloResponse
import org.covesa.uservice.example.hello_world.v1.Timer
import org.eclipse.uprotocol.UPClient
import org.eclipse.uprotocol.common.util.isOk
import org.eclipse.uprotocol.common.util.log.Formatter.stringify
import org.eclipse.uprotocol.common.util.toUStatus
import org.eclipse.uprotocol.core.usubscription.v3.USubscription
import org.eclipse.uprotocol.core.usubscription.v3.createTopicRequest
import org.eclipse.uprotocol.rpc.URpcListener
import org.eclipse.uprotocol.transport.unpack
import org.eclipse.uprotocol.v1.UMessage
import org.eclipse.uprotocol.v1.UPayload
import org.eclipse.uprotocol.v1.UPriority
import org.eclipse.uprotocol.v1.UStatus
import org.eclipse.uprotocol.v1.UUri
import org.eclipse.uprotocol.v1.forPublication
import org.eclipse.uprotocol.v1.forRpcRequest
import org.eclipse.uprotocol.v1.packToAny
import org.eclipse.uprotocol.v1.uAttributes
import org.eclipse.uprotocol.v1.uEntity
import org.eclipse.uprotocol.v1.uMessage
import org.eclipse.uprotocol.v1.uPayload
import org.eclipse.uprotocol.v1.uResource
import org.eclipse.uprotocol.v1.uUri
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HelloWorldService : LifecycleService() {
    private val tag = "HelloWorldService"

    private lateinit var mUPClient: UPClient

    private val rpcEventListener = RPCEventListener()

    private var uPFirstTimeConnection = true

    private val mExecutor = Executors.newScheduledThreadPool(0)

    private val createdTopicSet = ConcurrentHashMap.newKeySet<UUri>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForeground()
        Log.i(tag, "Starting UserService Service")
        initUPClient()
        beginPublishTimeOfDay()
    }

    override fun onDestroy() {
        shutdownUPClient()
        mExecutor.shutdownNow()
        super.onDestroy()
        Log.i(tag, "Service UserService onDestroy")
    }

    private fun startForeground() {
        val channelID = "org.eclipse.uprotocol.example.uphelloworldsrv.HelloWorldService"
        val channelName = "Hello World Service"
        val chan =
            NotificationChannel(
                channelID,
                channelName,
                NotificationManager.IMPORTANCE_NONE
            )
        chan.lightColor = Color.BLUE
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(chan)
        val notificationBuilder = NotificationCompat.Builder(this, channelID)
        val notification: Notification =
            notificationBuilder
                .setOngoing(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("uService is running in background")
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        ServiceCompat.startForeground(
            this,
            SERVICE_ID,
            notification,
            FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun beginPublishTimeOfDay() {
        mExecutor.scheduleAtFixedRate({
            publish(UURI_SECOND, uPayload { packToAny(buildTimeOfDay()) })
            if (Calendar.getInstance().get(Calendar.SECOND) == 0) {
                publish(UURI_MINUTE, uPayload { packToAny(buildTimeOfDay()) })
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun buildTimeOfDay(): Timer {
        val calender = Calendar.getInstance()
        return Timer.newBuilder().setTime(
            TimeOfDay.newBuilder()
                .setHours(calender.get(Calendar.HOUR_OF_DAY))
                .setMinutes(calender.get(Calendar.MINUTE))
                .setSeconds(calender.get(Calendar.SECOND)).build()
        ).build()
    }

    /**
     * Create the instance of UPClient and connect,
     * Register RPC Methods and create Topic when connection is established
     */
    private fun initUPClient() {
        mUPClient = UPClient.create(
            this,
            dispatcher = Dispatchers.IO,
            listener = object : UPClient.ServiceLifecycleListener {
                override fun onLifecycleChanged(client: UPClient, ready: Boolean) {
                    Log.i(tag, "Connect to UPClient, isReady: $ready")
                    if (uPFirstTimeConnection && ready) {
                        registerRPCMethods(RPC_SAY_HELLO)
                        createTopic(UURI_MINUTE)
                        createTopic(UURI_SECOND)
                        uPFirstTimeConnection = false
                    }
                    // reset uPFirstTimeConnection flag if UPClient is reconnected
                    if (!ready) {
                        uPFirstTimeConnection = true
                    }
                }
            }
        )
        scope.launch {
            try {
                mUPClient.connect()
            } catch (e: Exception) {
                e.toUStatus()
            }.foldLog("UPClient Connected")
        }
    }

    private fun shutdownUPClient() {
        scope.launch{
            unregisterRPCMethods(RPC_SAY_HELLO)
            mUPClient.disconnect()
        }
    }

    /**
     * Register the RPC methods so methods can be called by Apps
     */
    @Suppress("SameParameterValue")
    private fun registerRPCMethods(vararg methods: UUri) {
        mExecutor.execute {
            methods.forEach { method ->
                mUPClient.registerRpcListener(method, rpcEventListener)
                    .foldLog("Register ${method.stringify()}")
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun unregisterRPCMethods(vararg methods: UUri) {
        mExecutor.execute {
            methods.forEach { method ->
                mUPClient.unregisterRpcListener(method, rpcEventListener)
                    .foldLog("Unregister ${method.stringify()}")
            }
        }
    }

    /**
     * Publish UMessage to uBus, Only when UPClient is connected and topic has been created.
     */
    private fun publish(uri: UUri, uPayload: UPayload) {
        if (!mUPClient.isConnected() || !createdTopicSet.contains(uri)) return
        val message = uMessage {
            payload = uPayload
            attributes = uAttributes {
                forPublication(uri, UPriority.UPRIORITY_CS0)
            }
        }
        mUPClient.send(message).foldLog("Publish ${uri.stringify()}")
    }

    /**
     * Topic must be created before publishing
     */
    private fun createTopic(uri: UUri) {
        scope.launch {
            val createTopicRequest = createTopicRequest {
                topic = uri
            }
            USubscription.newStub(mUPClient).createTopic(createTopicRequest).catch {
                emit(it.toUStatus())
            }.collect {
                it.foldLog("Create Topic ${uri.stringify()}") {
                    createdTopicSet.add(uri)
                }
            }
        }
    }

    /**
     * Listener when RPC methods are called by App
     */
    inner class RPCEventListener : URpcListener {
        override fun onReceive(message: UMessage, response: CompletableDeferred<UPayload>) {
            when (message.attributes.sink) {
                RPC_SAY_HELLO -> processHelloRequest(message, response)
                // Add More RPC Calls here if needed
                else -> {
                    // Do nothing
                }
            }
        }

        /**
         * Handler for the SayHello! RPC method
         */
        private fun processHelloRequest(
            message: UMessage,
            completableDeferred: CompletableDeferred<UPayload>
        ) {
            val request : HelloRequest? = message.payload.unpack()
            val responseMessage =
                if (request == null) {
                    "Error Hello Request"
                } else if (request.name == "") {
                    "Hello World!"
                } else {
                    "Hello ${request.name}!"
                }
            val helloResponse = HelloResponse.newBuilder().setMessage(responseMessage).build()
            completableDeferred.complete(
                uPayload {
                    packToAny(helloResponse)
                }
            )
        }
    }

    private fun UStatus.foldLog(
        logDescription: String = "",
        onError: (status: UStatus) -> Unit = {},
        onSuccess: (status: UStatus) -> Unit = {}
    ) {
        if (isOk()) {
            logDescription.takeIf { it.isNotEmpty() }?.let {
                Log.i(tag, "$logDescription: ${stringify()}")
            }
            onSuccess(this)
        } else {
            logDescription.takeIf { it.isNotEmpty() }?.let {
                Log.e(tag, "$logDescription: ${stringify()}")
            }
            onError(this)
        }
    }

    companion object {
        private const val SERVICE_ID = 3

        private val SERVICE_UENTITY = uEntity {
            name = "example.hello_world"
            versionMajor = 1
        }

        private val RPC_SAY_HELLO: UUri = uUri {
            entity = SERVICE_UENTITY
            resource = uResource { forRpcRequest("SayHello") }
        }

        private val UURI_MINUTE: UUri = uUri {
            entity = SERVICE_UENTITY
            resource = uResource {
                name = "one_minute"
                message = "Timer"
            }
        }

        private val UURI_SECOND: UUri = uUri {
            entity = SERVICE_UENTITY
            resource = uResource {
                name = "one_second"
                message = "Timer"
            }
        }
    }
}
