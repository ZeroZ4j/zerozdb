# Publishing to Maven Central

Everything the build needs is already configured. What remains are the account and key steps,
which only you can do because they prove ownership of a domain and a signing key.

The old `oss.sonatype.org` route was retired in 2025. Releases now go through the **Central
Portal** at [central.sonatype.com](https://central.sonatype.com).

---

## 1. Create a Central Portal account

Sign up at [central.sonatype.com](https://central.sonatype.com) — sign in with GitHub, or with an
email and password. Nothing is published by creating the account.

## 2. Verify the `com.zeroz4j` namespace

This is the step that takes real-world time, because it proves you control the domain the groupId
is derived from.

**Finding the page:** it is not in the top navigation. Click your **username or email in the
top-right corner**, then **View Namespaces** in the dropdown.

1. **Add Namespace** → enter `com.zeroz4j` → submit. It appears as *Unverified*.
2. Click **Verify Namespace**. The state becomes *Verification Pending* and the portal shows the
   **Verification Key** assigned to the request.
3. Add a **DNS TXT record** on `zeroz4j.com` whose value is exactly that key. At most registrars
   this is Advanced DNS → Add Record → TXT, host `@`, value = the key. The check looks at the
   **exact** domain, so it must be on `zeroz4j.com` itself and not a subdomain.
4. Verification usually completes within minutes; the portal retries on its own.

Once verified you own `com.zeroz4j` and everything under it — including `com.zeroz4j:zerozstack-*`
later, without repeating any of this.

> **You may already have a namespace.** Signing up with GitHub grants `io.github.<username>`
> automatically, with no verification step. It works today and needs no DNS, but the coordinates
> stop matching the brand — worth using only as a fallback if the DNS record proves awkward.

## 3. Generate a publishing token

In the portal: **your name** → **View Account** → **Generate User Token**. It shows a username and
password pair *once*.

Put them in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>THE_TOKEN_USERNAME</username>
      <password>THE_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

The id must be `central` — that is what `central-publishing-maven-plugin` is configured to look for.

## 4. Create and publish a signing key

Central requires every artifact to be signed, and requires the public key to be findable.

```bash
# Generate. Choose RSA 4096, no expiry or a long one, and your published email address.
gpg --full-generate-key

# Find the key id (the long hex string after 'sec   rsa4096/')
gpg --list-secret-keys --keyid-format=long

# Publish the public key so Central can verify signatures
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
```

Then tell Maven the passphrase, in the same `~/.m2/settings.xml`:

```xml
<profiles>
  <profile>
    <id>release</id>
    <properties>
      <gpg.keyname>YOUR_KEY_ID</gpg.keyname>
      <gpg.passphrase>YOUR_PASSPHRASE</gpg.passphrase>
    </properties>
  </profile>
</profiles>
```

**Back up the private key somewhere safe.** Losing it does not invalidate what you published, but
you cannot sign future releases with the same identity.

## 5. Publish

```bash
mvn clean deploy -Prelease
```

The `release` profile attaches source and javadoc jars, signs everything, and uploads to the
portal. The build is configured with `autoPublish=false`, so the upload lands in the portal as a
**deployment awaiting your approval** rather than going straight out.

Check it at [central.sonatype.com/publishing/deployments](https://central.sonatype.com/publishing/deployments),
confirm the files and the POM look right, then click **Publish**.

It appears on Central within about 15 minutes, and on search.maven.org within a few hours.

Once you are confident in the process, set `autoPublish` to `true` in the plugin configuration and
`mvn deploy -Prelease` becomes a one-step release.

---

## Things that will bite

- **A published version can never be changed or removed.** This is why `autoPublish` is false here.
  If `0.1.0` goes out wrong, the only remedy is `0.1.1`.
- **`-SNAPSHOT` versions cannot be released.** The portal has a separate snapshot repository if you
  want one, but a release must be a fixed version.
- **Validation is strict about POM metadata.** `name`, `description`, `url`, `licenses`,
  `developers` and `scm` are all required — they are already in this POM, so this should pass, but
  it is the usual first-attempt failure for other projects.
- **The signature must verify against a published key.** If the key is not on a keyserver, or was
  published minutes ago and has not propagated, validation fails with a signature error rather than
  a helpful one.
- **Do not commit the token or passphrase.** They belong in `~/.m2/settings.xml`, which is outside
  the repository. For CI, use encrypted secrets and pass them as properties.

## Publishing ZeroZ Stack later

The namespace verification in step 2 covers the whole of `com.zeroz4j`, so the framework needs
only the same `release` profile — javadoc, sources, GPG and the central publishing plugin — copied
into its parent POM, plus the same metadata block (`url`, `licenses`, `developers`, `scm`). Its
multi-module build publishes every module in one `mvn deploy`.
