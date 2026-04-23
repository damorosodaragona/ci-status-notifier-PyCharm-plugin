package com.skillab.projector.cistatus

import com.intellij.util.messages.Topic

interface CiStatusRefreshListener {
    fun refreshRequested(reason: String)

    companion object {
        @JvmField
        val TOPIC: Topic<CiStatusRefreshListener> = Topic.create("CI Status Refresh", CiStatusRefreshListener::class.java)
    }
}

interface CiStatusJenkinsBuildListener {
    fun buildObserved(jobUrl: String, summary: JenkinsBuildSummary)

    companion object {
        @JvmField
        val TOPIC: Topic<CiStatusJenkinsBuildListener> = Topic.create("CI Status Jenkins Build", CiStatusJenkinsBuildListener::class.java)
    }
}
