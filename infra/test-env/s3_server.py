"""Test S3 server (moto). usage: s3_server.py <port>. Credentials: any (moto accepts signed requests), bucket "ndbucket"."""
import sys, time, boto3
from moto.server import ThreadedMotoServer

port = int(sys.argv[1])
srv = ThreadedMotoServer(ip_address="127.0.0.1", port=port, verbose=False)
srv.start()
s3 = boto3.client("s3", endpoint_url=f"http://127.0.0.1:{port}", aws_access_key_id="ndaccess", aws_secret_access_key="ndsecretkey", region_name="us-east-1")
s3.create_bucket(Bucket="ndbucket")
print("ready", flush=True)
while True:
    time.sleep(3600)
