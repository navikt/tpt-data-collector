package no.nav.checks

import kotlin.time.Instant

sealed class CheckResult(val name: String, val repo: String, val whenChecked: Instant)

class AllGood(name: String, repo: String, whenChecked: Instant) : CheckResult(name, repo, whenChecked)
class NeedsWork(name: String, repo: String, val reasons: List<String>, whenChecked: Instant) :
    CheckResult(name, repo, whenChecked)