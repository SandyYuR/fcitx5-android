# ClipCascade receive limits

The dedicated WebSocket transport validates decrypted frame headers before Okio
can deliver bytes to OkHttp's message assembler. Both a single frame and cumulative
fragments are bounded; ping/pong do not reset the message budget. Server masking,
reserved flags, invalid lengths/control frames and compression are rejected.
HTTP upgrade headers are limited to 64 KiB. Login HTTP uses its ordinary client;
upgrade sockets have their own HTTP/1.1 pool, with no idle connection reuse.

The default decoded payload ceiling is `min(32 MiB, max Java heap / 32)`. This
reserves half the heap for the IME/UI and allows a factor of 16 for transport,
UTF-16, Base64, JSON and encryption copies in the other half. The wire ceiling
includes both Base64 expansions and existing metadata/header allowances. This is
a conservative allocation budget, not a promise that an already exhausted app
heap can process a message. Existing outgoing queue and codec checks still apply.

Compatibility:

- Regular WS/WSS endpoints, cookies, default certificate/hostname checks and SNI
  are retained. TLS wraps the decrypted provider stream, using public socket APIs.
- Compression is not offered or accepted. The server must support uncompressed
  WebSocket messages.
- Configure the final upgrade address: it must respond with HTTP 101; WebSocket
  redirects are not followed. Ordinary login HTTP redirects retain their policy.
- Plain WS over SOCKS is rejected before an upgrade request because OkHttp creates
  that raw socket outside SocketFactory. WSS over SOCKS remains guarded; plain WS
  over HTTP proxies remains supported. A configured proxy is never silently bypassed.
- The 15-second call timeout applies to upgrade establishment; OkHttp exits that
  timeout on successful upgrade. Idle WebSocket reads remain unbounded and STOMP
  CONNECTED retains its separate deadline.

Tests use real WS and TLS sockets, fragmented UTF-8, malicious length declarations,
control frames, compression, cookies/SNI, certificate and hostname failures, and
proxy selection. A separate 64 MiB JVM, with compact strings disabled, rejects a
1 GiB declaration without reading any payload and receives a valid 4 MiB message
through the real OkHttp callback. Its sampled heap measurements describe that JVM
fixture; they are not Android device or whole-app peak measurements.
