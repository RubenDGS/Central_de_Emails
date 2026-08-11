package pt.caixa6

data class Account(
    val id: String,
    val label: String,
    val url: String
)

val DEFAULT_ACCOUNTS = listOf(
    Account("rita_sapo", "Rita Sapo", "https://mail.sapo.pt/"),
    Account("rita_gmail", "Rita Gmail", "https://mail.google.com/"),
    Account("mae_sapo", "Mãe Sapo", "https://mail.sapo.pt/"),
    Account("pai_sapo", "Pai Sapo", "https://mail.sapo.pt/"),
    Account("daniela_sapo", "Daniela Sapo", "https://mail.sapo.pt/"),
    Account("leonor_sapo", "Leonor Sapo", "https://mail.sapo.pt/")
)
