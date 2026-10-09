Optional trusted CA certificates for Docker Maven builds.

Place the trusted root/intermediate CA certificate supplied by your network
administrator or HTTPS inspection product here as a separate *.crt file.
PEM/Base-64 X.509 and DER X.509 certificates are supported by Java keytool.

Dockerfile imports these certificates into the BUILD-stage JVM trust store
before Maven downloads dependencies. No extra certificates are required
when the network uses a certificate chain already trusted by the base JDK.

Rebuild with:
  docker compose build --pull --no-cache app
  docker compose up -d

Detailed instructions: README.md, section about PKIX / certificate_unknown.
