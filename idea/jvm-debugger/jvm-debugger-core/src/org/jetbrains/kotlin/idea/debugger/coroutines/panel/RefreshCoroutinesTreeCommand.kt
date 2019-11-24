/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutines.panel

import com.intellij.debugger.DebuggerInvocationUtil
import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.engine.events.SuspendContextCommandImpl
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.ui.impl.watch.DebuggerTreeNodeImpl
import com.intellij.debugger.ui.impl.watch.MessageDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.MessageType
import com.intellij.xdebugger.impl.XDebuggerManagerImpl
import org.jetbrains.kotlin.idea.debugger.coroutines.CoroutineData
import org.jetbrains.kotlin.idea.debugger.coroutines.CoroutinesDebugProbesProxy
import org.jetbrains.kotlin.idea.debugger.coroutines.CoroutinesDebuggerTree
import org.jetbrains.kotlin.idea.debugger.evaluate.ExecutionContext
import java.lang.ref.WeakReference

class RefreshCoroutinesTreeCommand(
    private val mySession: DebuggerSession?,
    context: SuspendContextImpl?,
    val debuggerTree: CoroutinesDebuggerTree
) :  SuspendContextCommandImpl(context) {
    private val log = Logger.getInstance(this::class.java)

    override fun contextAction() {
        val root = debuggerTree.nodeFactory.defaultNode
        mySession ?: return
        val suspendContext = suspendContext
        if (suspendContext == null || suspendContext.isResumed) {
            debuggerTree.showMessage("Application resumed")
//                setRoot(root.apply { debuggerTree.add(debuggerTree.myNodeManager.createMessageNode("Application is resumed")) })
            return
        }
        val evaluationContext = EvaluationContextImpl(suspendContext, suspendContext.frameProxy)
        val executionContext = ExecutionContext(evaluationContext, suspendContext.frameProxy ?: return)
        val cache = debuggerTree.lastSuspendContextCache
        val states = if (cache != null && cache.first.get() === suspendContext) {
            cache.second
        } else CoroutinesDebugProbesProxy.dumpCoroutines(executionContext).apply {
            debuggerTree.lastSuspendContextCache = WeakReference(suspendContext) to this
        }
        // if suspend context hasn't changed - use last dump, else compute new
        if (states.isLeft) {
            log.warn(states.left)
            setRoot(root.apply {
                clear()
                add(debuggerTree.nodeFactory.createMessageNode(MessageDescriptor("Dump failed")))
            })
            XDebuggerManagerImpl.NOTIFICATION_GROUP
                .createNotification(
                    "Coroutine dump failed. See log",
                    MessageType.ERROR
                ).notify(debuggerTree.project)
            return
        }
        for (state in states.get()) {
            root.add(
                debuggerTree.nodeFactory.createNode(
                    debuggerTree.nodeFactory.getDescriptor(null, CoroutineData(state)), evaluationContext
                )
            )
        }
        setRoot(root)
    }

    private fun setRoot(root: DebuggerTreeNodeImpl) {
        DebuggerInvocationUtil.swingInvokeLater(debuggerTree.project) {
            debuggerTree.mutableModel.setRoot(root)
            debuggerTree.treeChanged()
        }
    }

}
