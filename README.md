# Akka Message Server

## Description

The Akka Message Server is an educational project designed to help individuals learn about building reactive and scalable web servers with Akka Streams and Akka HTTP in Scala. The project demonstrates how to leverage Akka actors and websockets to establish a dynamic messaging server where clients can register message sources and send messages to channels dynamically.

## Features

1. **Dynamic Channel Registration:** Clients can dynamically register message sources with unique keys.
2. **WebSocket Support:** Uses WebSockets to facilitate bidirectional message streams.
3. **Source Registry Actor:** A central entity that manages the registration and creation of message sources.
4. **Reactive Stream Handling:** Incorporates Akka Streams to efficiently manage data streams with backpressure support.

## Setup

```
java -version
openjdk version "21" 2023-09-19
OpenJDK Runtime Environment (build 21+35-2513)
OpenJDK 64-Bit Server VM (build 21+35-2513, mixed mode, sharing)
```

```
sbtVersion
[info] 1.9.5
```

```sh
./build
```

## Usage

With the server running, WebSocket clients can interact with it. Connect to a WebSocket using the URL ws://localhost:8080/channels/<CHANNEL_NAME>, replacing <CHANNEL_NAME> with the name of the channel you wish to register or send messages to.

Project Structure
The project contains two main Scala classes:

1. SourceRegistryActor:

Manages the registration of message sources.
Maintains a registry of created sources along with their respective message queues and streams.

2. MessageServer:
Initializes the HTTP server and establishes routes for the application.
Manages WebSocket connections and handles message exchanges.
Includes the main method to initiate the server and facilitate graceful shutdowns.

## Code Flow

The MessageServer object sets up the HTTP server and delineates the route-handling logic.
Upon receiving a request at the "/channels/<segment>" endpoint, it sends a RegisterSource message to the SourceRegistryActor.The SourceRegistryActor processes RegisterSource messages by either retrieving an existing source or creating a new one. The MessageServer configures a WebSocket message handler to process incoming text messages and merges them with the respective message source.

## Dependencies
- Akka Actor
- Akka Stream
- Akka HTTP

## Weaknesses:

1. Error Handling: The current implementation lacks comprehensive error handling. For instance, in case of an unsupported message type, it simply returns a text message instead of handling the error more robustly.

2. Scalability Concerns: The mutable Map (mutable.Map[String, SourceQueue]) used in SourceRegistryActor could potentially become a bottleneck in a highly concurrent scenario, and may also present issues with data consistency.

3. Resource Limits: The bounded message queue has a hardcoded size limit, which may not be flexible enough for varying load scenarios. Moreover, the server might face issues if the number of messages exceeds this limit.

4. Single Actor Instance: Creating a single instance of SourceRegistryActor might not distribute the load evenly across the system. Utilizing a pool of actors or a more sophisticated actor hierarchy might offer better load distribution and scalability.

5. Potential Memory Leaks: The registry map keeps growing as new sources are registered, which could lead to memory leaks over time as entries are never removed.


## Contributing

Contributions are warmly welcomed. Please fork the repository and create a pull request with your changes. Ensure to adhere to the existing coding style and include unit tests where necessary.

## License
This project is open-source, distributed under the MIT License.

## Contact
For queries or suggestions, feel free to open an issue on the GitHub repository.

