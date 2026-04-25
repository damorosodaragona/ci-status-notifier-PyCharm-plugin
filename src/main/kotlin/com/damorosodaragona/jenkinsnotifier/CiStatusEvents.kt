package com.damorosodaragona.jenkinsnotifier

import com.intellij.util.messages.Topic

interface CiStatusRefreshListener {
    fun refreshRequested(reason: String)

    companion object {
        @JvmField
        val TOPIC: Topic<CiStatusRefreshListener> = Topic.create("Jenkins CI Refresh", CiStatusRefreshListener::class.java)
    }
}

interface CiStatusJenkinsBuildListener {
    fun buildObserved(jobUrl: String, summary: JenkinsBuildSummary)

    companion object {
        @JvmField
        val TOPIC: Topic<CiStatusJenkinsBuildListener> = Topic.create("Jenkins CI Build", CiStatusJenkinsBuildListener::class.java)
    }
}
