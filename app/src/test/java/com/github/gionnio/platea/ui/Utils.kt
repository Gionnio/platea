package com.github.gionnio.platea.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult

@OptIn(ExperimentalTestApi::class)
fun SemanticsNodeInteraction.performClickEnter() =
    performKeyInput {
        pressKey(Key.DirectionCenter)
    }

fun <T> successResponse(content: T) = Response(content, 200, emptyMap())

fun successQueryResult(
    items: List<BaseItemDto> = emptyList(),
    totalRecordCount: Int = items.size,
) = successResponse(BaseItemDtoQueryResult(items, totalRecordCount, 0))

/** A mocked query answer holding [items]. */
fun mockQueryResult(items: List<BaseItemDto> = emptyList()): Response<BaseItemDtoQueryResult> {
    val queryResult = mockk<BaseItemDtoQueryResult> { every { this@mockk.items } returns items }
    return mockk { every { content } returns queryResult }
}
