# QU Microservices Cluster

## How to Run

Open CMD and navigate to the `src` folder:
```bash
cd src
```

Compile all files:
```bash
javac Server.java Client.java ServiceNode.java CompressionServiceNode.java ImageServiceNode.java CSVServiceNode.java
```

Open **3 separate terminal windows** in the `src` folder and run one command per window:

**Terminal 1 — Server:**
```bash
java Server
```

**Terminal 2 — Service Node:**
```bash
java ServiceNode <SERVER_IP> <PORT> <SERVICE_NAME>
```

**Terminal 3 — Client:**
```bash
java Client <SERVER_IP>
```

**Localhost examples:**
```bash
java ServiceNode 127.0.0.1 9100 BASE64
java Client 127.0.0.1
```

Once all three are running, use the Client terminal to list available services and send requests.
