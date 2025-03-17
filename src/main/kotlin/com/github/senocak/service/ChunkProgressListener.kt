package com.github.senocak.service

import com.github.senocak.logger
import org.slf4j.Logger
import org.springframework.batch.core.ChunkListener
import org.springframework.batch.core.StepExecution
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.stereotype.Component

@Component
class ChunkProgressListener: ChunkListener {
    private val log: Logger by logger()

    override fun afterChunk(context: ChunkContext) {
        val stepExecution: StepExecution = context.stepContext.stepExecution
        log.info("Chunk completed: ${context.isComplete}. StepExecution: $stepExecution")
    }

    override fun afterChunkError(context: ChunkContext) {
        val stepExecution: StepExecution = context.stepContext.stepExecution
        val jobId: String = stepExecution.jobExecution.jobParameters.getString("jobId")
            ?: stepExecution.jobExecution.id.toString()

        // Check if this is a skip rather than an error
        if (context.getAttribute("skip") != null || stepExecution.skipCount > 0) {
            log.info("Skip occurred in chunk for job $jobId, continuing processing")
        } else {
            log.warn("Error in chunk for job $jobId, StepExecution: $stepExecution")
        }
    }
}
