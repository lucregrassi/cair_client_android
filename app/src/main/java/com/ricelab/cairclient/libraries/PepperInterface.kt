package com.ricelab.cairclient.libraries

import android.util.Log
import com.aldebaran.qi.Future
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.`object`.actuation.*
import com.aldebaran.qi.sdk.`object`.geometry.Transform
import com.aldebaran.qi.sdk.builder.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PepperInterface"

class PepperInterface(
    private var qiContext: QiContext? = null
) {
    private var goToFuture: Future<Void>? = null

    fun setContext(qiContext: QiContext?) {
        this.qiContext = qiContext
    }

    suspend fun sayMessage(text: String) {
        if (qiContext != null) {
            try {
                withContext(Dispatchers.IO) {
                    val say = SayBuilder.with(qiContext)
                        .withText(text)
                        .build()
                    say.run()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during Say: ${e.message}")
            }
        } else {
            Log.e(TAG, "QiContext is not initialized. Cannot perform Say.")
        }
    }

    // Function to perform animations
    suspend fun performAnimation(animationRes: Int) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is not initialized.")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val animation: Animation = AnimationBuilder.with(qiContext)
                    .withResources(animationRes)
                    .build()

                val animate: Animate = AnimateBuilder.with(qiContext)
                    .withAnimation(animation)
                    .build()

                Log.i(TAG, "Starting animation")
                animate.run()
                Log.i(TAG, "Animation completed successfully.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during animation: ${e.message}", e)
        }
    }

    // Function to move the robot
    fun moveRobot(x: Double, y: Double, theta: Double) {
        if (qiContext == null) {
            Log.e(TAG, "QiContext is not initialized.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val actuation: Actuation = qiContext!!.actuation
                val robotFrame: Frame = actuation.robotFrame()
                val transform: Transform = TransformBuilder.create().from2DTransform(x, y, theta)
                val mapping: Mapping = qiContext!!.mapping

                val targetFrame: FreeFrame = mapping.makeFreeFrame()
                targetFrame.update(robotFrame, transform, System.currentTimeMillis())

                val goTo = GoToBuilder.with(qiContext)
                    .withFrame(targetFrame.frame())
                    .withFinalOrientationPolicy(OrientationPolicy.ALIGN_X)
                    .withMaxSpeed(0.3F)
                    .withPathPlanningPolicy(PathPlanningPolicy.STRAIGHT_LINES_ONLY)
                    .build()

                goToFuture = goTo.async().run()
                goToFuture?.thenConsume { future ->
                    if (future.isSuccess) {
                        Log.i(TAG, "GoTo action finished successfully.")
                    } else if (future.hasError()) {
                        Log.e(TAG, "GoTo action finished with error.", future.error)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during robot movement: ${e.message}", e)
            }
        }
    }

    // Function to stop the robot
    fun stopRobot() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                goToFuture?.requestCancellation()
                goToFuture = null
                Log.i(TAG, "Movement stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during stopping robot: ${e.message}", e)
            }
        }
    }
}