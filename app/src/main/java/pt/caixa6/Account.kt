package pt.caixa6

data class Account(
    val id: String,
    val label: String,
    val url: String
)

val DEFAULT_ACCOUNTS = listOf(
    Account("sapo1", "SAPO 1", "https://mail.sapo.pt/"),
    Account("sapo2", "SAPO 2", "https://mail.sapo.pt/"),
    Account("sapo3", "SAPO 3", "https://mail.sapo.pt/"),
    Account("sapo4", "SAPO 4", "https://mail.sapo.pt/"),
    Account("sapo5", "SAPO 5", "https://mail.sapo.pt/"),
    Account("gmail", "Gmail", "https://mail.google.com/")
)
