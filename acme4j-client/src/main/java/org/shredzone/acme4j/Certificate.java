/*
 * acme4j - Java ACME client
 *
 * Copyright (C) 2016 Richard "Shred" Körber
 *   http://acme4j.shredzone.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */
package org.shredzone.acme4j;

import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableList;

import java.io.IOException;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyPair;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import edu.umd.cs.findbugs.annotations.Nullable;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.shredzone.acme4j.connector.Resource;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeLazyLoadingException;
import org.shredzone.acme4j.exception.AcmeNotSupportedException;
import org.shredzone.acme4j.exception.AcmeProtocolException;
import org.shredzone.acme4j.toolbox.AcmeUtils;
import org.shredzone.acme4j.toolbox.JSONBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents an issued certificate and its certificate chain.
 * <p>
 * A certificate is immutable once it is issued. For renewal, a new certificate must be
 * ordered.
 */
public class Certificate extends AcmeResource {
    private static final long serialVersionUID = 7381527770159084201L;
    private static final Logger LOG = LoggerFactory.getLogger(Certificate.class);

    private @Nullable List<X509Certificate> certChain;
    private @Nullable Collection<URL> alternates;
    private transient @Nullable RenewalInfo renewalInfo = null;
    private transient @Nullable List<Certificate> alternateCerts = null;

    protected Certificate(Login login, URL certUrl) {
        super(login, certUrl);
    }

    /**
     * Downloads the certificate chain.
     * <p>
     * The certificate is downloaded lazily by the other methods. Usually there is no need
     * to invoke this method, unless the download is to be enforced. If the certificate
     * has been downloaded already, nothing will happen.
     *
     * @throws AcmeException
     *         if the certificate could not be downloaded
     */
    public void download() throws AcmeException {
        if (certChain == null) {
            LOG.debug("download");
            try (var conn = getSession().connect()) {
                conn.sendCertificateRequest(getLocation(), getLogin());
                alternates = conn.getLinks("alternate");
                certChain = conn.readCertificates();
            }
        }
    }

    /**
     * Returns the created certificate.
     *
     * @return The created end-entity {@link X509Certificate} without issuer chain.
     */
    public X509Certificate getCertificate() {
        lazyDownload();
        return requireNonNull(certChain).get(0);
    }

    /**
     * Returns the created certificate and issuer chain.
     *
     * @return The created end-entity {@link X509Certificate} and issuer chain. The first
     *         certificate is always the end-entity certificate, followed by the
     *         intermediate certificates required to build a path to a trusted root.
     */
    public List<X509Certificate> getCertificateChain() {
        lazyDownload();
        return unmodifiableList(requireNonNull(certChain));
    }

    /**
     * Returns URLs to alternate certificate chains.
     *
     * @return Alternate certificate chains, or empty if there are none.
     */
    public List<URL> getAlternates() {
        lazyDownload();
        return requireNonNull(alternates).stream().collect(toUnmodifiableList());
    }

    /**
     * Returns alternate certificate chains, if available.
     *
     * @return Alternate certificate chains, or empty if there are none.
     * @since 2.11
     */
    public List<Certificate> getAlternateCertificates() {
        if (alternateCerts == null) {
            var login = getLogin();
            alternateCerts = getAlternates().stream()
                    .map(login::bindCertificate)
                    .collect(toUnmodifiableList());
        }
        return alternateCerts;
    }

    /**
     * Writes the certificate to the given writer. It is written in PEM format, with the
     * end-entity cert coming first, followed by the intermediate certificates.
     *
     * @param out
     *            {@link Writer} to write to. The writer is not closed after use.
     */
    public void writeCertificate(Writer out) throws IOException {
        try {
            for (var cert : getCertificateChain()) {
                AcmeUtils.writeToPem(cert.getEncoded(), AcmeUtils.PemLabel.CERTIFICATE, out);
            }
        } catch (CertificateEncodingException ex) {
            throw new IOException("Encoding error", ex);
        }
    }

    /**
     * Returns this certificate's CertID according to RFC 6960.
     * <p>
     * This method requires the {@link org.bouncycastle.jce.provider.BouncyCastleProvider}
     * security provider.
     *
     * @see <a href="https://www.rfc-editor.org/rfc/rfc6960.html">RFC 6960</a>
     * @since 3.0.0
     */
    public String getCertID() {
        var certChain = getCertificateChain();
        if (certChain.size() < 2) {
            throw new AcmeProtocolException("Certificate has no issuer");
        }

        try {
            var builder = new JcaDigestCalculatorProviderBuilder();
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) {
                builder.setProvider(BouncyCastleProvider.PROVIDER_NAME);
            }
            var digestCalc = builder.build().get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256));
            var issuerHolder = new X509CertificateHolder(certChain.get(1).getEncoded());
            var certId = new CertificateID(digestCalc, issuerHolder, certChain.get(0).getSerialNumber());
            return AcmeUtils.base64UrlEncode(certId.toASN1Primitive().getEncoded());
        } catch (Exception ex) {
            throw new AcmeProtocolException("Could not compute Certificate ID", ex);
        }
    }

    /**
     * Returns the location of the certificate's RenewalInfo. Empty if the CA does not
     * provide this information.
     *
     * @since 3.0.0
     */
    public Optional<URL> getRenewalInfoLocation() {
        try {
            return getSession().resourceUrlOptional(Resource.RENEWAL_INFO)
                    .map(baseUrl -> {
                        try {
                            var url = baseUrl.toExternalForm();
                            if (!url.endsWith("/")) {
                                url += '/';
                            }
                            url += getCertID();
                            return new URL(url);
                        } catch (MalformedURLException ex) {
                            throw new AcmeProtocolException("Invalid RenewalInfo URL", ex);
                        }
                    });
        } catch (AcmeException ex) {
            throw new AcmeLazyLoadingException(this, ex);
        }
    }

    /**
     * Returns {@code true} if the CA provides renewal information.
     *
     * @since 3.0.0
     */
    public boolean hasRenewalInfo() {
        return getRenewalInfoLocation().isPresent();
    }

    /**
     * Reads the RenewalInfo for this certificate.
     *
     * @return The {@link RenewalInfo} of this certificate.
     * @since 3.0.0
     */
    public RenewalInfo getRenewalInfo() {
        if (renewalInfo == null) {
            renewalInfo = getRenewalInfoLocation()
                    .map(getLogin()::bindRenewalInfo)
                    .orElseThrow(() -> new AcmeNotSupportedException("renewal-info"));
        }
        return renewalInfo;
    }

    /**
     * Revokes this certificate.
     */
    public void revoke() throws AcmeException {
        revoke(null);
    }

    /**
     * Revokes this certificate.
     *
     * @param reason
     *            {@link RevocationReason} stating the reason of the revocation that is
     *            used when generating OCSP responses and CRLs. {@code null} to give no
     *            reason.
     * @see #revoke(Login, X509Certificate, RevocationReason)
     * @see #revoke(Session, KeyPair, X509Certificate, RevocationReason)
     */
    public void revoke(@Nullable RevocationReason reason) throws AcmeException {
        revoke(getLogin(), getCertificate(), reason);
    }

    /**
     * Revoke a certificate.
     * <p>
     * Use this method if the certificate's location is unknown, so you cannot regenerate
     * a {@link Certificate} instance. This method requires a {@link Login} to your
     * account and the issued certificate.
     *
     * @param login
     *         {@link Login} to the account
     * @param cert
     *         The {@link X509Certificate} to be revoked
     * @param reason
     *         {@link RevocationReason} stating the reason of the revocation that is used
     *         when generating OCSP responses and CRLs. {@code null} to give no reason.
     * @see #revoke(Session, KeyPair, X509Certificate, RevocationReason)
     * @since 2.6
     */
    public static void revoke(Login login, X509Certificate cert, @Nullable RevocationReason reason)
                throws AcmeException {
        LOG.debug("revoke");

        var session = login.getSession();

        var resUrl = session.resourceUrl(Resource.REVOKE_CERT);

        try (var conn = session.connect()) {
            var claims = new JSONBuilder();
            claims.putBase64("certificate", cert.getEncoded());
            if (reason != null) {
                claims.put("reason", reason.getReasonCode());
            }

            conn.sendSignedRequest(resUrl, claims, login);
        } catch (CertificateEncodingException ex) {
            throw new AcmeProtocolException("Invalid certificate", ex);
        }
    }

    /**
     * Revoke a certificate.
     * <p>
     * Use this method if the key pair of your account was lost (so you are unable to
     * login into your account), but you still have the key pair of the affected domain
     * and the issued certificate.
     *
     * @param session
     *         {@link Session} connected to the ACME server
     * @param domainKeyPair
     *         Key pair the CSR was signed with
     * @param cert
     *         The {@link X509Certificate} to be revoked
     * @param reason
     *         {@link RevocationReason} stating the reason of the revocation that is used
     *         when generating OCSP responses and CRLs. {@code null} to give no reason.
     * @see #revoke(Login, X509Certificate, RevocationReason)
     */
    public static void revoke(Session session, KeyPair domainKeyPair, X509Certificate cert,
            @Nullable RevocationReason reason) throws AcmeException {
        LOG.debug("revoke using the domain key pair");

        var resUrl = session.resourceUrl(Resource.REVOKE_CERT);

        try (var conn = session.connect()) {
            var claims = new JSONBuilder();
            claims.putBase64("certificate", cert.getEncoded());
            if (reason != null) {
                claims.put("reason", reason.getReasonCode());
            }

            conn.sendSignedRequest(resUrl, claims, session, domainKeyPair);
        } catch (CertificateEncodingException ex) {
            throw new AcmeProtocolException("Invalid certificate", ex);
        }
    }

    /**
     * Lazily downloads the certificate. Throws a runtime {@link AcmeLazyLoadingException}
     * if the download failed.
     */
    private void lazyDownload() {
        try {
            download();
        } catch (AcmeException ex) {
            throw new AcmeLazyLoadingException(this, ex);
        }
    }

}
