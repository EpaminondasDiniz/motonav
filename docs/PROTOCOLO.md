# Protocolo do painel (observado em campo)

Como o MotoNav conversa com o painel TFT da moto. Tudo aqui foi observado com um painel real
(Zontes T350X, modelo `ZHKJ08-1122`, 800×480), com o objetivo de **interoperabilidade**: usar o
próprio painel com outro app. O app se identifica com o próprio nome de pacote, não usa contas nem
credenciais de terceiros e não altera firmware nem configurações do painel.

Os identificadores abaixo são **exemplos fictícios**.

## 1. Conexão

1. **QR code do painel**: um link com parâmetros, por exemplo
   `...?modelid=NNNNN&action=8&ssid=XXXX&pwd=XXXX&auth=WPA2&mac=aa%3Abb%3Acc%3Add%3Aee%3Aff&name=XXXX`.
   Só `mac` e `name` são usados. O SSID e a senha **não** são usados.
2. **Wi-Fi Direct** (`WifiP2pManager`): busca de dispositivos, depois `connect` com o MAC do QR,
   `WpsInfo.PBC` e `groupOwnerIntent = 0`. O painel vira dono do grupo, com IP `192.168.0.1`; o celular
   fica com `192.168.0.2`. Não há Bluetooth na conexão.
3. O celular abre servidores TCP nas portas **10922**, **10921** e **10920**.
4. **Wakeup**: TCP do celular para `192.168.0.1:10930`, enviando PXC `0x70000010` com
   `{"phoneType":"Android","packageName":"<seu pacote>"}`. Resposta: `0x70000011 {"status":true}`.
5. O painel conecta **duas vezes** na 10922 (C2P e depois P2C), depois na 10921 e por fim na 10920.

## 2. Enquadramento PXC (portas 10930 e 10922)

Cabeçalho de 16 bytes, **little-endian**: `[int cmd][int tamanhoTotal][int cmd^tamanhoTotal][int 0]`
seguido do payload (normalmente JSON UTF-8). A resposta a um comando X é X+1.

O primeiro pacote de cada conexão na 10922 diz o tipo do canal, e o celular responde `tipo+1` vazio:
- `0x00010000`: C2P (painel → celular)
- `0x00020000`: P2C (celular → painel)

### C2P (o painel pergunta, o celular responde)

| Cmd | Conteúdo | Resposta |
|---|---|---|
| `0x00010010` | apresentação do painel (JSON com HUID, HUName, mirrorMode, supportFunction...) | `0x00010011` com o JSON do celular (abaixo) |
| `0x70000000` | sinal de vida, a cada ~3 s | `0x70000001` vazio |
| `0x000103E0` | número de série do painel | `+1` vazio |
| `0x00010450` | hora/fuso | `+1` vazio |
| `0x00010040`, `0x000104A0` | informações do fabricante (o `0x104A0` traz dados de atualização: **ignorar**) | `+1` vazio |
| `0x00010430` | o painel pede a posição GPS do celular (a cada ~2 s) | `+1` vazio |
| `0x00010630` | o painel pede o clima (a cada ~2 s) | `+1` vazio |
| `0x00010500` | tecla `{actionId, keyCode}`, **não apareceu em campo** (os botões do guidão são tratados pelo próprio painel) | `+1` vazio |
| `0x00010200` | modo noturno `{night}`, não apareceu | `+1` vazio |
| outro par | — | `0x80000010` (não suportado) |
| ímpar | — | descartar |

Resposta à apresentação (`0x00010011`):

```json
{"pxcVersion":"1.0.2","phoneUUID":"<uuid>","phoneBrand":"...","phoneModel":"...",
 "phoneOsVersion":"<sdk>","phoneOs":"Android","package":"<seu pacote>","versionCode":1,
 "token":0,"pubkey":"<chave RSA pública, base64>","encryptedHUID":"<HUID cifrado com a sua chave privada>",
 "bluetoothName":"...","supportH264IFrame":true,"supportFunction":<o valor que o painel mandou>}
```

O par RSA-1024 é gerado no próprio celular. O painel não verifica o `encryptedHUID`.

**P2C**: o celular manda `0x00020020` a cada ~1 s com
`{"displayRotation":0,"width":W,"height":H,"enableAccessibility":false,"enableAOAHid":false,"mode":2}`,
e o painel responde `0x00020021`.

A faixa de baixo do painel (giro, marcha, combustível, temperatura) é desenhada pelo próprio painel:
o protocolo **não** manda essa telemetria para o celular.

## 3. Vídeo: controle (porta 10921)

Cabeçalho de 8 bytes LE: `[short cmd][short tamanhoCorpo][int 0]` + corpo.

| Cmd | Direção | Corpo |
|---|---|---|
| `0x0010` | painel → celular | configuração pedida: `short w, short h, int fps, int encoder, int codec, short minQ, short maxQ, int bitrate`, flags e um bloco base64 |
| `0x0011` | celular → painel | `[int encoder=2 (H.264)][short w][short h]` |
| `0x0040` / `0x0041` | sinal de vida / resposta | vazio |
| `0x0070` / `0x0071` | iniciar vídeo / confirmação | vazio |
| `0x7F02` | resposta "não suportado" | vazio |

## 4. Vídeo: dados (porta 10920)

- O painel manda `0x0072` (cabeçalho de 8 bytes sem corpo) cerca de 30 vezes por segundo.
- O celular responde `[int tamanho LE][quadro H.264 Annex-B]`, ou `00 00 00 00` se não houver quadro
  novo. Antes de cada quadro-chave, mande também `[int tamanho][SPS/PPS]`.
- O que funcionou bem: 800×480, ~30 fps, quadro-chave a cada 1 s, CBR de até 3 Mbps. Com muito mais
  que isso o painel chegou a fechar o vídeo.
- Se nenhum quadro chegar por muito tempo, o painel fecha o vídeo. Repita o último quadro (~10/s).
- Ao sair da projeção pelo botão do guidão, o painel **fecha 10920/10921 e mantém o C2P**; ao voltar,
  reabre o vídeo sozinho.

## 5. Dicas

- Force a parada do app oficial antes de testar: ele conecta primeiro.
- Com a tela do celular apagada, o Android pausa telas virtuais. O app copia o `TextureView` do mapa
  (modo "captura") para continuar mandando quadros.
- A Economia de energia de alguns aparelhos desliga o GPS com a tela apagada.
