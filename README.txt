Files:
MathClient.java - TCP Client object code
MathServer.java - TCP Server object code
logging.txt - Server logs events (CONNECT, REQUEST, DISCONNECT) with clients along with their information
run_test.ps1 - PowerShell executable for automatically compiling and executing server and three clients with test inputs
Makefile - make file for manual compilation and testing of client-server application

For automated execution in Windows PowerShell:
- ensure you are in the directory that contains all of the aforementioned files (use command: cd "C:.../directory")
- ensure that you have permission to execute scripts on your device (use this command: Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass)
- execute the executable file with the following command: ./run_test.ps1
- four terminals should pop up, the first the server that mentions the port they are listening at and the next three are test client terminals
connecting to the server with test values
- execution is too fast to see (an issue we could not fix) before the client terminals disappear, but server terminal remains open with outputs indicating clients information and their requests
- to close the server, use the command: Get-Process java | Stop-Process
- open logging.txt to view logged client information with their connect, request and disconnect events

For manual testing in Windows PowerShell:
- ensure you are in the directory that contains all of the aforementioned files (use command: cd "C:.../directory")
- ensure that you have permission to execute scripts on your device (use this command: Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass)
- compile server and client:
javac MathServer.java
javac MathClient.java
- now class files will be visible in directory:
MathServer.class
MathClient.class
- run the server: java MathServer
- expected output on server terminal: MathServer is listening on port 5000.
- in another (one or more) terminal(s) (ensure in the same directory and have permission to execute scripts), compile client(s): java MathClient
- enter math expressions +-/* with multiple operands, invalid expressions or 'exit' to disconnect
- server disconnects if you close the terminal
- open logging.txt to view logged client information with their connect, request and disconnect events
