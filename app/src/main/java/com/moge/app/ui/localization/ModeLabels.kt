package com.moge.app.ui.localization

import com.moge.app.R
import com.moge.app.core.TextResources
import com.moge.app.domain.ConversationMode
import com.moge.app.domain.SolveMode

fun TextResources.label(mode: ConversationMode): String = text(when (mode) {
    ConversationMode.SOLVE -> R.string.ui_solve_e903b
    ConversationMode.CHAT -> R.string.ui_chat_00d4c
})

fun TextResources.label(mode: SolveMode): String = text(when (mode) {
    SolveMode.DETAILED -> R.string.ui_standard_solution_e207d
    SolveMode.CHECK_WORK -> R.string.ui_check_my_work_d3fae
})
