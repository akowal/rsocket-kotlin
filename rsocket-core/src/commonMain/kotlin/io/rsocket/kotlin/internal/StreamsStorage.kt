/*
 * Copyright 2015-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin.internal

import io.ktor.utils.io.core.internal.*
import io.ktor.utils.io.pool.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.handler.*

internal class StreamsStorage(private val isServer: Boolean, private val pool: ObjectPool<ChunkBuffer>) {
    private val streamId: StreamId = StreamId(isServer)
    private val handlers: IntMap<FrameHandler> = IntMap()

    fun nextId(): Int = streamId.next(handlers)

    fun save(id: Int, handler: FrameHandler) {
        handlers[id] = handler
    }

    fun remove(id: Int): FrameHandler? {
        return handlers.remove(id)?.also(FrameHandler::close)
    }

    fun contains(id: Int): Boolean {
        return id in handlers
    }

    fun cleanup(error: Throwable?) {
        val values = handlers.values()
        handlers.clear()
        values.forEach {
            it.cleanup(error)
            it.close()
        }
    }

    fun handleFrame(frame: Frame, responder: RSocketResponder) {
        val id = frame.streamId
        when (frame) {
            is RequestNFrame -> handlers[id]?.handleRequestN(frame.requestN)
            is CancelFrame   -> handlers[id]?.handleCancel()
            is ErrorFrame    -> handlers[id]?.handleError(frame.throwable)
            is RequestFrame  -> when {
                frame.type == FrameType.Payload -> handlers[id]?.handleRequest(frame) ?: frame.close() // release on unknown stream id
                isServer.xor(id % 2 != 0)       -> frame.close() // request frame on wrong stream id
                else                            -> {
                    val initialRequest = frame.initialRequest
                    val handler = when (frame.type) {
                        FrameType.RequestFnF      -> ResponderFireAndForgetFrameHandler(id, this, responder, pool)
                        FrameType.RequestResponse -> ResponderRequestResponseFrameHandler(id, this, responder, pool)
                        FrameType.RequestStream   -> ResponderRequestStreamFrameHandler(id, this, responder, initialRequest, pool)
                        FrameType.RequestChannel  -> ResponderRequestChannelFrameHandler(id, this, responder, initialRequest, pool)
                        else                      -> error("Wrong request frame type") // should never happen
                    }
                    save(id, handler)
                    handler.handleRequest(frame)
                }
            }
            else             -> frame.close()
        }
    }
}