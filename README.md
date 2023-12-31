# Akka Message Server

## Description

The Akka Message Server is an educational project designed to help individuals learn about building reactive and scalable web servers with Akka Streams and Akka HTTP in Scala. The project demonstrates how to leverage Akka actors and websockets to establish a dynamic messaging server where clients can register message sources and send messages to channels dynamically.

This implementation provides a real-time messaging server where users can subscribe to various channels to exchange messages. Leveraging the Akka framework, it offers a scalable and resilient solution, efficiently handling channel registrations and message subscriptions through WebSocket connections. It maintains a dynamic registry of channels and subscribers, ensuring optimal resource usage and easy management of user connections.

The goal is to provide a simple message server which can be used to establish a chat application. Or any other use case where a peer to peer
message exchange is required. 

[![Akka Message Server in Action](https://i.ytimg.com/an_webp/VEyg1Pa8d1w/mqdefault_6s.webp?du=3000&sqp=COiyt6gG&rs=AOn4CLD3BHIDa5wmYxWA0d2KGHXC-OP7qg)](https://youtu.be/VEyg1Pa8d1w "Akka Message Server in Action")

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

## Key Concepts

1. Akka Actors: The server uses the actor model to manage concurrent, distributed, and resilient message-driven applications.
2. Akka HTTP: A toolkit for building connection-level and application-level APIs, utilizing the reactive streams approach.
3. Akka Streams: A library to process and transfer a sequence of elements using bounded buffer space.
4. Akka Peristence: All incoming messages will be persisted in order to replay messages after server connection initialisation.


## Usage

To use this messaging server, deploy it to a server, and use WebSocket clients to connect to it by navigating to ws://<server_address>:8080/channels/<channel_name>/users/<user_name>.

## Weaknesses:

1. Error Handling: The current implementation has limited error handling, particularly in the WebSocket flow where all exceptions are caught and a simple text message is returned. A more robust error handling mechanism could provide richer error information and different responses depending on the error type.

2. Logging and Monitoring: Although there is some logging implemented, adding more comprehensive logging and monitoring would be beneficial to trace the system's behavior and identify potential issues quickly.

3. Code Modularity: The ChannelRegistryActor is handling multiple responsibilities, including managing channels and monitoring subscribers. This could be broken down into smaller, more focused components to adhere better to the Single Responsibility Principle.

4. Concurrency and Synchronization: The use of mutable collections (like mutable.Set) might lead to concurrency issues if not handled carefully. It's better to avoid mutable state or use structures that are designed to handle concurrent access safely.

5. Resource Cleanup: Currently, the termination of channels is based on the termination of subscribers. A more sophisticated cleanup strategy could be implemented to better manage resources, especially in scenarios with fluctuating numbers of active channels and subscribers.

6. Security: The current state lacks security features like authentication and authorization, leaving the channels open to unauthorized access and potential misuse.

7. Scalability Concerns: The use of a single actor (ChannelRegistryActor) as a centralized registry might become a bottleneck in a system with a high number of channels and subscribers.

8. Testing: The current state lacks unit and integration tests, which are critical for ensuring the reliability of the system, especially when making changes or additions to the codebase.


## Next Steps

- Tests: Unit and integration tests.
- User management: An API next to the actual websocket route should provide endpoints to modify channels, inivitations and subscriptions.
- Message Format: The messages should get a suitable format with an unique identifier and a message type definition (Ack, Nack, Heartbeat, Message, etc.)
- Akka Persistence: A DynamoDB/PostgreSQL adapter should be integrated.
- Akka Cluster: A cluster configuration should ensure that the setup would work in a distributed environment.
- Ack/Nack handling: An acknowledgement message should remove already delivered messages.
- Serialization: The messages should be serialized as JSON (on the wire and in the database).
- Authentication: An OAuth IDP setup (keycloak - on prem, SaaS: AWS Cognito, Okta/Auth0, ...).
- Encryption: An e2e encryption should be integrated.
- Deployment: Docker setup for an on prem deployment. Cloud setup on AWS as Beanstalk or Fargate deployment. 
- Tor: For an on prem setup would it be possible to think about an Tor + VPN setup to hide the server origin.  
- Frontend: A simple mobile app (register server, sending/receiving messages etc.)

## Contributing

Contributions are warmly welcomed. Please fork the repository and create a pull request with your changes. Ensure to adhere to the existing coding style and include unit tests where necessary.

## License
This project is open-source, distributed under the MIT License.

## Contact
For queries or suggestions, feel free to open an issue on the GitHub repository.

