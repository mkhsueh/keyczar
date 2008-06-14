/*
 * Copyright 2008 Google Inc.
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

package com.google.keyczar;

import com.google.gson.annotations.Expose;
import com.google.keyczar.enums.KeyType;
import com.google.keyczar.exceptions.KeyczarException;
import com.google.keyczar.interfaces.DecryptingStream;
import com.google.keyczar.interfaces.EncryptingStream;
import com.google.keyczar.interfaces.SigningStream;
import com.google.keyczar.interfaces.Stream;
import com.google.keyczar.interfaces.VerifyingStream;
import com.google.keyczar.util.Base64Coder;
import com.google.keyczar.util.Util;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.ShortBufferException;


/**
 * Wrapping class for RSA Private Keys
 * 
 * @author steveweis@gmail.com (Steve Weis)
 * 
 */
class RsaPrivateKey extends KeyczarPrivateKey {
  private static final String CRYPT_ALGORITHM = "RSA/ECB/OAEPWITHSHA1ANDMGF1PADDING";
  private static final String KEY_GEN_ALGORITHM = "RSA";
  
  @Expose private RsaPublicKey publicKey;

  private static final String SIG_ALGORITHM = "SHA1withRSA";

  private RsaPrivateKey() {
    publicKey = new RsaPublicKey();
  }

  @Override
  Stream getStream() throws KeyczarException {
    return new RsaPrivateStream();
  }

  @Override
  String getKeyGenAlgorithm() {
    return KEY_GEN_ALGORITHM;
  }

  @Override
  KeyType getType() {
    return KeyType.RSA_PRIV;
  }

  @Override
  KeyczarPublicKey getPublic() {
    return publicKey;
  }

  @Override
  void setPublic(KeyczarPublicKey pub) throws KeyczarException {
    publicKey = (RsaPublicKey) pub;
    publicKey.init();
  }
  
  static RsaPrivateKey read(String input) throws KeyczarException {
    RsaPrivateKey key = Util.gson().fromJson(input, RsaPrivateKey.class);
    if (key.getType() != KeyType.RSA_PRIV) {
      throw new KeyczarException("Incorrect type. Received: " + key.getType());
    }
    if (!Arrays.equals(key.hash(), key.getPublic().hash())) {
      throw new KeyczarException("Key hash does not match");
    }
    key.init();
    key.getPublic().init();
    return key;
  }
  
  static RsaPrivateKey generate() throws KeyczarException {
    RsaPrivateKey key = new RsaPrivateKey();
    try {
      KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_GEN_ALGORITHM);
      kpg.initialize(key.getType().defaultSize());
      KeyPair pair = kpg.generateKeyPair();
      key.jcePrivateKey = pair.getPrivate();
      key.getPublic().set(pair.getPublic().getEncoded());
    } catch (GeneralSecurityException e) {
      throw new KeyczarException(e);
    }
    key.hash = key.getPublic().hash();
    key.pkcs8 = Base64Coder.encode(key.jcePrivateKey.getEncoded());
    key.init();
    key.getPublic().init();
    return key;
  }

  private class RsaPrivateStream implements SigningStream, VerifyingStream,
      DecryptingStream, EncryptingStream {
    private Cipher cipher;
    private EncryptingStream encryptingStream;
    private Signature signature;
    private VerifyingStream verifyingStream;

    public RsaPrivateStream() throws KeyczarException {
      try {
        signature = Signature.getInstance(SIG_ALGORITHM);
        verifyingStream = (VerifyingStream) publicKey.getStream();
        cipher = Cipher.getInstance(CRYPT_ALGORITHM);
        encryptingStream = (EncryptingStream) publicKey.getStream();
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }

    public int digestSize() {
      return getType().getOutputSize();
    }

    @Override
    public int doFinalDecrypt(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      try {
        return cipher.doFinal(input, output);
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public int doFinalEncrypt(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      return encryptingStream.doFinalEncrypt(input, output);
    }

    @Override
    public SigningStream getSigningStream() throws KeyczarException {
      return encryptingStream.getSigningStream();
    }

    @Override
    public VerifyingStream getVerifyingStream() {
      return new VerifyingStream() {
        @Override
        public int digestSize() {
          return 0;
        }

        @Override
        public void initVerify() {
          // Do nothing
        }

        @Override
        public void updateVerify(ByteBuffer input) {
          // Do nothing
        }

        @Override
        public boolean verify(ByteBuffer signature) {
          // Do nothing
          return true;
        }
      };
    }

    @Override
    public void initDecrypt(ByteBuffer input) throws KeyczarException {
      try {
        cipher.init(Cipher.DECRYPT_MODE, getJcePrivateKey());
      } catch (InvalidKeyException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public int initEncrypt(ByteBuffer output) throws KeyczarException {
      return encryptingStream.initEncrypt(output);
    }

    @Override
    public void initSign() throws KeyczarException {
      try {
        signature.initSign(getJcePrivateKey());
      } catch (GeneralSecurityException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public void initVerify() throws KeyczarException {
      verifyingStream.initVerify();
    }

    @Override
    public int maxOutputSize(int inputLen) {
      // TODO Auto-generated method stub
      return getType().getOutputSize() * 2;
    }

    @Override
    public void sign(ByteBuffer output) throws KeyczarException {
      try {
        byte[] sig = signature.sign();
        output.put(sig);
      } catch (SignatureException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public int updateDecrypt(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      try {
        return cipher.update(input, output);
      } catch (ShortBufferException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public int updateEncrypt(ByteBuffer input, ByteBuffer output)
        throws KeyczarException {
      return encryptingStream.updateEncrypt(input, output);
    }

    @Override
    public void updateSign(ByteBuffer input) throws KeyczarException {
      try {
        signature.update(input);
      } catch (SignatureException e) {
        throw new KeyczarException(e);
      }
    }

    @Override
    public void updateVerify(ByteBuffer input) throws KeyczarException {
      verifyingStream.updateVerify(input);
    }

    @Override
    public boolean verify(ByteBuffer sig) throws KeyczarException {
      return verifyingStream.verify(sig);
    }
  }

}
