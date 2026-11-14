"""Test SFTP server (asyncssh). usage: sftp_server.py <port> <root> <hostkey-file>
User nd / password ndpass. Host key is created if missing (delete the file to rotate it)."""
import asyncio, os, sys
import asyncssh

port, root, keyfile = int(sys.argv[1]), sys.argv[2], sys.argv[3]
os.makedirs(root, exist_ok=True)


AUTH_LOG = os.environ.get("ND_AUTH_LOG")
WEAK = os.environ.get("ND_WEAK") == "1"


class Srv(asyncssh.SSHServer):
    def password_auth_supported(self):
        return True

    def validate_password(self, username, password):
        if AUTH_LOG:  # records that a credential reached the server (never the credential itself)
            with open(AUTH_LOG, "a") as f:
                f.write(f"auth-attempt user={username}" + chr(10))
        return username == "nd" and password == "ndpass"


class SFTP(asyncssh.SFTPServer):
    def __init__(self, chan):
        super().__init__(chan, chroot=os.fsencode(root))


async def main():
    if not os.path.exists(keyfile):
        asyncssh.generate_private_key("ssh-rsa" if WEAK else "ssh-ed25519").write_private_key(keyfile)
    if WEAK:  # a legacy server: only SHA-1 / CBC / group14-sha1 / ssh-rsa -> the client must refuse
        kw = dict(encryption_algs=["aes128-cbc"], mac_algs=["hmac-sha1"], kex_algs=["diffie-hellman-group14-sha1"], signature_algs=["ssh-rsa"])
    else:  # modern algorithms only, like a hardened OpenSSH
        kw = dict(encryption_algs=["chacha20-poly1305@openssh.com", "aes256-gcm@openssh.com"])
    await asyncssh.listen("127.0.0.1", port, server_factory=Srv, server_host_keys=[keyfile], sftp_factory=SFTP, **kw)
    print("ready", flush=True)
    await asyncio.Event().wait()


asyncio.run(main())
