package pt.caixa6

data class Account(
    val id: String,
    val label: String,
    val url: String
)

private const val SAPO_INBOX =
    "https://mail.sapo.pt/v7/#/messages/SU5CT1g"

val DEFAULT_ACCOUNTS = listOf(
    Account("rita_sapo", "Rita Sapo", SAPO_INBOX),
    Account("rita_gmail", "Rita Gmail", "https://mail.google.com/"),
    Account("mae_sapo", "Mãe Sapo", SAPO_INBOX),
    Account("pai_sapo", "Pai Sapo", SAPO_INBOX),
    Account("daniela_sapo", "Daniela Sapo", SAPO_INBOX),
    Account("leonor_sapo", "Leonor Sapo", SAPO_INBOX)
)
