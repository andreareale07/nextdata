"""usage: mkcert.py <cert.pem> <key.pem> [cn]  -- self-signed EC cert for 127.0.0.1 (test only)"""
import sys, datetime, ipaddress
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
key = ec.generate_private_key(ec.SECP256R1())
cn = sys.argv[3] if len(sys.argv) > 3 else "nd-test"
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=30))
        .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
        .sign(key, hashes.SHA256()))
open(sys.argv[1], "wb").write(cert.public_bytes(serialization.Encoding.PEM))
open(sys.argv[2], "wb").write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
