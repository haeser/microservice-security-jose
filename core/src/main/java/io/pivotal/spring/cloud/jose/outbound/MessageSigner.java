/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.pivotal.spring.cloud.jose.outbound;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.Map.Entry;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.pivotal.spring.cloud.jose.Constants;
import lombok.Getter;

public class MessageSigner {

	private static final JWSAlgorithm jwsAlgorithm = JWSAlgorithm.RS256;
	@Getter
	private final RSAPublicKey publicKey;
	private final JWSSigner signer;
	private final String issuer;
	private final SecureRandom secureRandom;

	public MessageSigner(String issuer) {
		this.issuer = issuer;
		try {
			KeyPairGenerator keyGenerator = KeyPairGenerator.getInstance("RSA");
			keyGenerator.initialize(2048);
			KeyPair keypair = keyGenerator.genKeyPair();
			publicKey = (RSAPublicKey) keypair.getPublic();
			RSAPrivateKey privateKey = (RSAPrivateKey) keypair.getPrivate();
			signer = new RSASSASigner(privateKey);
			secureRandom = new SecureRandom();
		} catch (NoSuchAlgorithmException e) {
			throw new SigningException("Cannot create RSA keypair", e);
		}
	}

	public SignedMessage sign(Message message) {
		try {
			message.validate();
		} catch (InvalidMessageException e) {
			throw new SigningException("Cannot sign an invalid message.", e);
		}
		String jti = randomBase64URLString(128);
		String tokenEnvelope = getEncodedAndSignedTokenEnvelope(message, jti);
		String body = null;
		if (message.getBody() != null) {
			body = getEncodedAndSignedBody(message, jti);
		}
		return new SignedMessage(tokenEnvelope, body);
	}

	private SignedJWT getSignedJwt(JWTClaimsSet jwtClaims) {
		JWSHeader jwtHeader = new JWSHeader.Builder(jwsAlgorithm)
				.type(JOSEObjectType.JWT)
				.build();
		SignedJWT signedJWT = new SignedJWT(jwtHeader, jwtClaims);
		try {
			signedJWT.sign(signer);
		} catch (JOSEException e) {
			throw new SigningException("Cannot sign JWT.", e);
		}
		return signedJWT;
	}

	private JWSObject getJwsEnvelopedJwt(SignedJWT signedJwt) {
		JWSHeader jwsHeader = new JWSHeader.Builder(jwsAlgorithm)
				.type(JOSEObjectType.JOSE)
				.keyID(issuer)
				.contentType(Constants.JWT_CONTENT_TYPE)
				.build();
		Payload payload = new Payload(signedJwt);
		JWSObject jws = new JWSObject(jwsHeader, payload);
		try {
			jws.sign(signer);
		} catch (JOSEException e) {
			throw new SigningException("Cannot sign JWS envelope for JWT.", e);
		}
		return jws;
	}

	private JWTClaimsSet getJwtClaims(Message message, String jti) {
		JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder();
		claimsBuilder
				.jwtID(jti)
				.issuer(issuer)
				.audience(message.getAudience())
				.issueTime(new Date())
				.expirationTime(getExpirationTime(message));
		claimsBuilder.claim(Constants.OPERATION_CLAIM, message.getOperation());
		if (message.getInitialToken() != null) {
			claimsBuilder.claim(Constants.INITIAL_TOKEN_CLAIM, message.getInitialToken());
		}
		if (message.getParentToken() != null) {
			claimsBuilder.claim(Constants.PARENT_JWT_CLAIM, message.getParentToken());
		}
		if (message.getCustomClaims() != null && !message.getCustomClaims().isEmpty()) {
			for (Entry<String, Object> entry : message.getCustomClaims().entrySet()) {
				claimsBuilder.claim(entry.getKey(), entry.getValue());
			}
		}
		if (message.getBody() != null) {
			claimsBuilder.claim(Constants.BODY_CLAIM, true);
		}
		return claimsBuilder.build();
	}

	private String getEncodedAndSignedTokenEnvelope(Message message, String jti) {
		JWTClaimsSet jwtClaims = getJwtClaims(message, jti);
		SignedJWT signedJwt = getSignedJwt(jwtClaims);
		JWSObject jwsEnvelopedJwt = getJwsEnvelopedJwt(signedJwt);
		return jwsEnvelopedJwt.serialize();
	}

	private String getEncodedAndSignedBody(Message message, String jti) {
		JWSHeader jwsHeader = new JWSHeader.Builder(jwsAlgorithm)
				.type(JOSEObjectType.JOSE)
				.contentType(message.getContentType())
				.customParam(Constants.JWT_ID_CLAIM, jti)
				.build();
		Payload payload = new Payload(message.getBody());
		JWSObject jws = new JWSObject(jwsHeader, payload);
		try {
			jws.sign(signer);
		} catch (JOSEException e) {
			throw new SigningException("Cannot sign JWS body.", e);
		}
		return jws.serialize();
	}

	private Date getExpirationTime(Message message) {
		return new Date(System.currentTimeMillis() + message.getTtlSeconds() * 1000);
	}

	private String randomBase64URLString(int bitsOfEntropy) {
		byte[] bytes = new byte[bitsOfEntropy / 8];
		secureRandom.nextBytes(bytes);
		return Base64URL.encode(bytes).toString();
	}

}
