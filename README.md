# Caixa6 — protótipo Android

Objetivo: ter **5 sessões SAPO Mail + 1 Gmail na mesma aplicação**, sem IMAP/POP e sem Premium.

## Como funciona

A app não tenta usar IMAP/POP. É um pequeno browser dedicado ao email, construído com GeckoView (motor do Firefox).

- cria 6 sessões isoladas;
- as 5 sessões SAPO usam `mail.sapo.pt`;
- a sexta usa Gmail;
- cada sessão tem cookies/storage próprios através de um `contextId`;
- um serviço em primeiro plano tenta manter as caixas "abertas" em segundo plano;
- notificações Web geradas pelo SAPO/Gmail são transformadas em notificações Android.

## Primeiro uso

1. Abrir a app.
2. Tocar em **SAPO 1** e fazer login na primeira conta.
3. Repetir em SAPO 2, SAPO 3, SAPO 4 e SAPO 5.
4. Abrir Gmail e fazer login.
5. No SAPO Mail, ativar a opção de notificações se o site a apresentar.
6. Permitir as notificações Android quando a app pedir.

As passwords **não são guardadas pelo código da Caixa6**. O login fica guardado no armazenamento/cookies do motor Gecko no próprio telemóvel.

## Importante

Isto é um **protótipo**. A ideia é tecnicamente adequada ao problema porque trabalha pelo webmail, que continua permitido, em vez de usar IMAP/POP.

A parte que precisa de teste num Samsung real é a fiabilidade das notificações SAPO em segundo plano. O SAPO documenta que o webmail tem de estar aberto no browser; a Caixa6 tenta satisfazer isso mantendo as 5 sessões abertas e o processo vivo com um serviço em primeiro plano.

Há um pequeno aviso permanente "Caixa6 ativa". É intencional: ajuda o Android a não matar o processo.

## Compilar sem instalar Android Studio

O repositório inclui `.github/workflows/build-apk.yml`.

1. Criar um repositório privado no GitHub.
2. Carregar o conteúdo desta pasta.
3. Abrir **Actions → Build APK → Run workflow**.
4. No fim, descarregar o artefacto **Caixa6-debug-apk**.
5. Dentro dele está `app-debug.apk`.

## Segurança

- Recomenda-se repositório **privado**.
- Não colocar passwords em ficheiros, código, GitHub Secrets ou configuração.
- Os logins são feitos diretamente nas páginas oficiais SAPO/Gmail.
- O código não envia credenciais para nenhum servidor próprio.

## Limitações conhecidas

- O SAPO pode alterar o webmail e o comportamento das notificações.
- Android/Samsung pode limitar processos em segundo plano apesar do serviço.
- Gmail é aberto como webmail dentro da mesma app, não por API Gmail.
- Este protótipo ainda não foi testado numa conta SAPO real.
