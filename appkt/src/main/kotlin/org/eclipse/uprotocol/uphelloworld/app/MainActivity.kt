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

package org.eclipse.uprotocol.uphelloworld.app

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.protobuf.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.covesa.uservice.example.hello_world.v1.HelloRequest
import org.covesa.uservice.example.hello_world.v1.HelloResponse
import org.covesa.uservice.example.hello_world.v1.Timer
import org.eclipse.uprotocol.UPClient
import org.eclipse.uprotocol.common.util.isOk
import org.eclipse.uprotocol.common.util.log.Formatter.stringify
import org.eclipse.uprotocol.common.util.toUStatus
import org.eclipse.uprotocol.core.usubscription.v3.SubscriberInfo
import org.eclipse.uprotocol.core.usubscription.v3.SubscriptionRequest
import org.eclipse.uprotocol.core.usubscription.v3.USubscription
import org.eclipse.uprotocol.core.usubscription.v3.UnsubscribeRequest
import org.eclipse.uprotocol.rpc.CallOptions
import org.eclipse.uprotocol.rpc.toResponse
import org.eclipse.uprotocol.transport.UListener
import org.eclipse.uprotocol.transport.unpack
import org.eclipse.uprotocol.uphelloworld.app.databinding.ActivityMainBinding
import org.eclipse.uprotocol.v1.UCode
import org.eclipse.uprotocol.v1.UMessage
import org.eclipse.uprotocol.v1.UStatus
import org.eclipse.uprotocol.v1.UUri
import org.eclipse.uprotocol.v1.forRpcRequest
import org.eclipse.uprotocol.v1.packToAny
import org.eclipse.uprotocol.v1.uEntity
import org.eclipse.uprotocol.v1.uPayload
import org.eclipse.uprotocol.v1.uResource
import org.eclipse.uprotocol.v1.uUri
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {
    private val tag = "HelloWorldApp"

    private lateinit var binding: ActivityMainBinding

    private lateinit var mUPClient: UPClient

    private val eventListenerMap = ConcurrentHashMap<UUri, UListener>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * jv#0 Create an android application with a pre-required UI
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        initUPClient()
        addButtonListener()
    }

    override fun onDestroy() {
        shutdownUPClient()
        super.onDestroy()
    }

    private fun shutdownUPClient() {
        scope.launch {
            mUPClient.disconnect()
        }
    }

    /**
     * jv#3 Register Event Listener
     *   > Add On Click Listener for Buttons (define handlers for the given buttons/on-click events)
     */
    private fun addButtonListener() {
        with(binding) {
            buttonSayHello.setOnClickListener {
                triggerSayHelloRPC()
            }

            buttonSubSec.setOnClickListener {
                subscribeToUUri<Timer>(UURI_SECOND) { message ->
                    runOnUiThread {
                        val time = message.time
                        textViewSecDisplay.text =
                            getString(R.string.time_display, time.hours, time.minutes, time.seconds)
                    }
                }
            }

            buttonUnsubSec.setOnClickListener {
                unsubscribeToUUri(UURI_SECOND)
                textViewSecDisplay.text = getString(R.string.textView_default_time)
            }

            buttonSubMin.setOnClickListener {
                subscribeToUUri<Timer>(UURI_MINUTE) { message ->
                    runOnUiThread {
                        val time = message.time
                        textViewMinDisplay.text =
                            getString(R.string.time_display, time.hours, time.minutes, time.seconds)
                    }
                }
            }

            buttonUnsubMin.setOnClickListener {
                unsubscribeToUUri(UURI_MINUTE)
                textViewMinDisplay.text = getString(R.string.textView_default_time)
            }
        }
    }

    /**
     * jv#1 Create new instance of UPClient and connect to it
     */
    private fun initUPClient() {
        mUPClient = UPClient.create(
            this,
            dispatcher = Dispatchers.IO,
            listener = object : UPClient.ServiceLifecycleListener {
                override fun onLifecycleChanged(client: UPClient, ready: Boolean) {
                    Log.i(tag, "Connect to UPClient, isReady: $ready")
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

    /**
     * jv#2a Subscribe to "given" UURIs
     */
    private inline fun <reified T : Message> subscribeToUUri(
        uri: UUri,
        crossinline action: (message: T) -> Unit
    ) {
        scope.launch {
            val request = SubscriptionRequest.newBuilder().setTopic(uri)
                .setSubscriber(SubscriberInfo.newBuilder().setUri(mUPClient.uri).build())
                .build()
            USubscription.newStub(mUPClient).subscribe(request).catch {
                Log.e(tag, "Failed to subscribe: ${it.toUStatus()}")
            }.collect { response ->
                val status = response.status
                if (response.status.code != UCode.OK) {
                    Log.e(tag, "Failed to subscribe: $status")
                } else {
                    Log.i(tag, "Subscribed: $status")
                    val eventListener = object : UListener {
                        override fun onReceive(message: UMessage) {
                            message.payload.unpack<T>()?.let { uMessage ->
                                action(uMessage)
                            }
                        }
                    }
                    mUPClient.registerListener(uri, eventListener).foldLog(
                        "Register Listener ${uri.stringify()}"
                    ) {
                        eventListenerMap[uri] = eventListener
                    }
                }
            }
        }
    }

    /**
     * jv#2b Unsubscribe from "given" UURIs
     */
    private fun unsubscribeToUUri(uri: UUri) {
        scope.launch {
            val request = UnsubscribeRequest.newBuilder()
                .setTopic(uri)
                .setSubscriber(SubscriberInfo.newBuilder().setUri(mUPClient.uri).build())
                .build()
            USubscription.newStub(mUPClient).unsubscribe(request).catch { emit(it.toUStatus()) }.collect { status ->
                status.foldLog("Unsubscribed ${uri.stringify()}")
            }
            eventListenerMap[uri]?.let {
                mUPClient.unregisterListener(uri, it).foldLog(
                    "Unregister Listener ${uri.stringify()}"
                ) {
                    eventListenerMap.remove(uri)
                }
            }
        }
    }

    /**
     * jv#4 Invoke RPC method when user make RPC calls
     */
    private fun triggerSayHelloRPC() {
        scope.launch(Dispatchers.Main) {
            mUPClient.invokeMethod(
                RPC_SAY_HELLO,
                uPayload {
                    packToAny(
                        HelloRequest.newBuilder().setName(binding.editTextName.text.toString()).build()
                    )
                },
                CallOptions.DEFAULT
            ).toResponse<HelloResponse>().flowOn(Dispatchers.IO).catch {
                Log.e(tag, "Failed to invoke $it")
            }.collect { response ->
                Log.i(tag, "rpc response $response")
                binding.textViewResponse.text = response.message
            }
        }
    }

    private fun UStatus.foldLog(
        logDescription: String = "",
        onError: (status: UStatus) -> Unit = {},
        onSuccess: (status: UStatus) -> Unit = {}
    ) {
        if (this.isOk()) {
            logDescription.takeIf { it.isNotEmpty() }?.let {
                Log.i(tag, "$logDescription: ${this.stringify()}")
            }
            onSuccess(this)
        } else {
            logDescription.takeIf { it.isNotEmpty() }?.let {
                Log.e(tag, "$logDescription: ${this.stringify()}")
            }
            onError(this)
        }
    }

    companion object {
        private val HELLO_USER_SERVICE_UENTITY = uEntity {
            name = "example.hello_world"
            versionMajor = 1
        }
        val RPC_SAY_HELLO: UUri = uUri {
            entity = HELLO_USER_SERVICE_UENTITY
            resource = uResource { forRpcRequest("SayHello") }
        }
        val UURI_MINUTE: UUri = uUri {
            entity = HELLO_USER_SERVICE_UENTITY
            resource = uResource {
                name = "one_minute"
                message = "Timer"
            }
        }
        val UURI_SECOND: UUri = uUri {
            entity = HELLO_USER_SERVICE_UENTITY
            resource = uResource {
                name = "one_second"
                message = "Timer"
            }
        }
    }
}
