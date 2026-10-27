FROM ubuntu:18.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends python3.6 python3-pip python3-dev libc++1-9 libc++abi1-9 libatomic1 && rm -rf /var/lib/apt/lists/*
RUN ln -sf /usr/bin/python3.6 /usr/bin/python && python3.6 -m pip install --no-cache-dir pip==21.3.1 "setuptools<60" wheel
RUN python3.6 -m pip install --no-cache-dir numpy==1.19.5 onnx==1.8.1 protobuf==3.17.3 pyyaml mako packaging six
WORKDIR /work
