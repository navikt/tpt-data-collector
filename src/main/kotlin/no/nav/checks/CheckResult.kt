package no.nav.checks

sealed class CheckResult(val name: String, val repo: String)

class AllGood(name: String, repo: String) : CheckResult(name, repo)
class NeedsWork(name: String, repo: String, val reasons: List<String>) : CheckResult(name, repo)