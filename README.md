# QU Microservices Cluster

A distributed networking system where clients submit computational tasks to a dynamic pool of worker nodes. Built with Java using TCP for task delivery and UDP for heartbeats and service discovery.

---

## Prerequisites

### Java

Java JDK 21 or higher must be installed on every machine running any part of this project. Verify your installation:
```bash
java -version
javac -version
```
If not installed, download the JDK from `https://www.oracle.com/java/technologies/downloads/`

---

### Tailscale (Required for Multi-Device Setup)

Tailscale creates a private virtual network across all your devices so they can communicate with each other regardless of what network each one is on. Every machine that will run the Server, Client, or a Service Node must be connected to the same Tailscale network.

**Step 1 — Create a Tailscale account:**

Go to `tailscale.com` and sign up for a free account. Everyone on the team shares the same account.

**Step 2 — Install Tailscale on every device:**

Go to `tailscale.com/download` on each machine and install it. After installing, sign in using the shared team account. Once signed in, Tailscale runs automatically in the background.

**Step 3 — Verify all devices are connected:**

Run this on any machine to see all connected devices and their Tailscale IPs:
```bash
tailscale status
```
Every device should appear in this list with a `100.x.x.x` IP address. Note down the Tailscale IP of the machine that will run the Server — this is the IP used in all Service Node and Client run commands.

---

### Deployment Options

You can run this project in three different configurations. Regardless of which option you choose, **all devices must be connected to the same Tailscale network** before starting.

**Option A — Multiple Devices (Recommended)**
Run the Server and Client on separate machines (laptops, desktops, or cloud VMs) and run one Service Node per additional device. Use each device's Tailscale IP for all commands.

**Option B — AWS EC2 + Laptops**
Run the Server on an AWS EC2 instance and the Service Nodes on local laptops. Install Tailscale on the EC2 instance via SSH:
```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```
Install Tailscale on each laptop via the installer at `tailscale.com/download`. Open ports `9000` (TCP) and `9001` (UDP) in your EC2 Security Group inbound rules. Use the EC2 instance's Tailscale IP in all run commands.

**Option C — Single Machine (Localhost)**
Run everything on one machine using `127.0.0.1` as the IP. No Tailscale setup is needed for this option. Useful for testing but does not demonstrate real distributed networking.

---

## Compilation

Navigate to the `src` folder and compile all files:
```bash
cd src
javac Server.java Client.java ServiceNode.java CSVServiceNode.java CompressionServiceNode.java HMACServiceNode.java ImageServiceNode.java TopKServiceNode.java
```

---

## Running the System

Open a separate terminal window for each component. Run them in this order:

**Terminal 1 — Server:**
```bash
java Server
```

**Terminal 2+ — Service Nodes (one per laptop):**
```bash
java <ServiceNodeClass> <SERVER_TAILSCALE_IP> <PORT>
```

**Last Terminal — Client:**
```bash
java Client <SERVER_TAILSCALE_IP>
```

> Wait 15–30 seconds after starting the Service Nodes before launching the Client so heartbeats have time to register.

---

## Service Nodes

Each service node has its own class and must use a unique port number.

| Class | Service Name | Run Command |
|-------|-------------|-------------|
| `CSVServiceNode` | `CSV` | `java CSVServiceNode <SERVER_IP> <PORT>` |
| `CompressionServiceNode` | `COMPRESSION` | `java CompressionServiceNode <SERVER_IP> <PORT>` |
| `HMACServiceNode` | `HMAC` | `java HMACServiceNode <SERVER_IP> <PORT>` |
| `ImageServiceNode` | `IMAGE` | `java ImageServiceNode <SERVER_IP> <PORT>` |
| `TopKServiceNode` | `TOPK` | `java TopKServiceNode <SERVER_IP> <PORT> [filter=on|off]` |

**Example (all on localhost):**
```bash
java CSVServiceNode 127.0.0.1 9100
java CompressionServiceNode 127.0.0.1 9101
java HMACServiceNode 127.0.0.1 9102
java ImageServiceNode 127.0.0.1 9103
java TopKServiceNode 127.0.0.1 9104
java Client 127.0.0.1
```

---

## Service Usage

Once the client is running, type `1` to list available services and `2` to send a request.

---

**CSV** — Analyzes a list of comma-separated numbers and returns statistics.

Input a list of numbers separated by commas:
```
1.5, 2.5, 3.5, 4.5, 5.5
```
Returns `mean` (average), `median` (middle value), `std` (how spread out the values are), `min` (lowest value), and `max` (highest value).

---

**COMPRESSION** — Compresses or decompresses text and files using GZIP.

When prompted, enter one of these operations:
- `COMPRESS` — takes your input and compresses it, returning a Base64-encoded string
- `DECOMPRESS` — takes a previously compressed Base64 string and restores the original

You will then be asked whether your input is text (type directly) or a file (provide a file path). If using a file, you will also be prompted for an output file path to save the result.

---

**HMAC** — Cryptographically signs or verifies a message using a secret key. Use this to confirm a message has not been tampered with.

| Parameter | Description |
|-----------|-------------|
| `mysecretkey` | A password-like string used to generate the signature — both the signer and verifier must use the exact same key |
| `Hello World` | The message being signed or verified |
| `<base64signature>` | The signature string produced by SIGN — copy and paste it into VERIFY to check it |

Sign a message:
```
SIGN|mysecretkey|Hello World
```
Returns a Base64-encoded signature string. Copy this to use in VERIFY.

Verify a message:
```
VERIFY|mysecretkey|Hello World|<paste signature here>
```
Returns `VALID` if the message and key match the signature, `INVALID` if they do not.

---

**IMAGE** — Transforms an image file. You will be prompted for an operation, an input file path, and an output file path to save the result.

| Operation | Description |
|-----------|-------------|
| `grayscale` | Converts the image to black and white |
| `thumbnail` | Shrinks the image to a 128x128 pixel thumbnail |
| `rotate:90` | Rotates the image clockwise by the given degrees (e.g. `rotate:90`, `rotate:180`, `rotate:270`) |
| `resize:800x600` | Resizes the image to the exact width x height in pixels you specify |

---

**TOPK** — Analyzes text and returns the most significant words. Common words like "the", "and", and "is" are automatically filtered out.

There are two operations:

`TOPK` — finds the K most frequently used words in a single piece of text:
```
TOPK|5|The quick brown fox jumps over the lazy dog
```

| Parameter | Description |
|-----------|-------------|
| `5` | K — how many top words to return |
| `The quick brown...` | The text to analyze |

`TFIDF` (Term Frequency-Inverse Document Frequency) — ranks words by how unique and important they are across multiple documents. Words that appear in every document score lower because they are less distinctive:
```
TFIDF|5|Machine learning is great~~Deep learning is also great~~Learning never stops
```

| Parameter | Description |
|-----------|-------------|
| `5` | K — how many top words to return |
| `~~` | Separator between documents — place two tildes between each document |
| Each section between `~~` | One document — the first document is analyzed, the rest are used for comparison |

The optional `filter=off` flag when launching TopKServiceNode disables stop-word filtering so all words including "the" and "and" are counted:
```bash
java TopKServiceNode 127.0.0.1 9104 filter=off
```

---

## Notes

- If a Service Node is not running, its service will not appear in the list
- If a node goes offline, it is removed from the available list after 120 seconds
- When a node restarts, it re-registers automatically on its next heartbeat (15–30 seconds)
- Port 9000 must be open for TCP client connections
- Port 9001 must be open for UDP heartbeats

---

## Credits

| Name | Role |
|------|------|
| Alex Pina | Programmer |
| Ben Bailor | Programmer |
| Jackson Sennhenn | Programmer |
| Tyler Smalley | Programmer |
