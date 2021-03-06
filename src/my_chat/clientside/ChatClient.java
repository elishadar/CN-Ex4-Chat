package my_chat.clientside;

import my_chat.data_transfer.MessageInput;
import my_chat.data_transfer.MessageOutput;
import my_chat.message_types.*;
import my_chat.data_transfer.ChatListener;
import my_chat.serverside.ChatServer;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

/**
 * @author Elisha
 */
public class ChatClient implements Runnable
{
	private String serverAddress;
	@SuppressWarnings("FieldCanBeLocal")
	private ObjectInputStream in;
	private ObjectOutputStream out;

	private String name = "";

	private final ChatListener listener;

	public ChatClient(ChatListener listener, String serverAddress)
	{
		this.listener = listener;
		this.serverAddress = serverAddress;
	}

	public void updateServerAddress(String serverAddress)
	{
		this.serverAddress = serverAddress;
	}

	/**
	 * @param name a new name.
	 */
	public void updateName(String name)
	{
		synchronized (nameUpdateLock)
		{
			this.name = name;
			nameUpdateLock.notifyAll();
		}
	}

	/**
	 * Checks if a name is valid. Note: this doe not check if the name is already taken!
	 */
	public boolean isValidName(String name)
	{
		return Pattern.matches(ChatServer.nameRegexPattern, name);
	}

	private Object nameUpdateLock = new Object();

	private boolean running = false;
	private boolean loggedIn = false;
	private final Object clientRunningLock = new Object();

	/**
	 * Start the Client.
	 * You should run this from a separate thread.
	 * Because this class implements Runnable, you can run:
	 * <pre>
	 * {@code
	 * Thread thread = new Thread(client);
	 * thread.start();
	 * }
	 * </pre>
	 * where 'client' is an instance of this class.
	 */
	@Override
	public void run()
	{
		synchronized (clientRunningLock)
		{
			listener.stat("Client starting ...", false);

			try
			{
				var socket = new Socket(serverAddress, ChatServer.port);
				out = new ObjectOutputStream(socket.getOutputStream());
				in = new ObjectInputStream(socket.getInputStream());

				running = true;

				listener.stat("Client started", false);

				if (name != null && !name.trim().equals(""))
					sendMessage(new LoginMessage(name, true));

				// log in
				while (!loggedIn)
				{
					Object obj = in.readObject();
					if (obj instanceof IServerMessage)
					{
						IServerMessage sm = (IServerMessage) obj;
						messageReceived(sm);

						if (sm instanceof LoginResponseMessage)
						{
							LoginResponseMessage am = (LoginResponseMessage) sm;
							if (am.accepted)
							{
								loggedIn = true;
							}
						} else if (sm instanceof LoginRequestMessage)
						{
							synchronized (nameUpdateLock)
							{
								nameUpdateLock.wait();
								sendMessage(new LoginMessage(name, true));
							}
						}
					} else
					{
						listener.stat("A non-server send was received. Message dropped.", true);
					}
				}

				// send / receive messages
				while (running)
				{
					Object obj = in.readObject();
					if (obj instanceof IServerMessage)
					{
						IServerMessage sm = (IServerMessage) obj;
						messageReceived(sm);
					} else
					{
						listener.stat("A non-server send was received. Message dropped.", true);
					}
				}

			} catch (IOException e)
			{
				listener.stat("IOException. Exiting.", true);
			} catch (ClassNotFoundException e)
			{
				listener.stat("Problem reading from stream. Exiting.", true);
			} catch (InterruptedException e)
			{
				e.printStackTrace();
			} finally
			{
				stop();
			}
		}
	}

	/**
	 * Stops the client and logs out.
	 */
	public synchronized void stop()
	{
		listener.stat("Stopping ...", false);
		if (loggedIn)
		{
			sendMessage(new LoginMessage(name, false));
			loggedIn = false;
		}

		if (out != null)
			try
			{
				out.close();
			} catch (IOException e)
			{
				e.printStackTrace();
			}
		running = false;
		listener.stat("Client stopped", false);
	}

	/**
	 * @return true if the client is running.
	 */
	public boolean isRunning()
	{
		return running;
	}

	/**
	 * Send a send to all the other users. You can only call this after the client has started.
	 *
	 * @param message the send to send.
	 */
	public synchronized void messageAll(String message)
	{
		sendMessage(new ChatMessage(name, null, message));
	}

	/**
	 * Send a private send to one user. You can only call this after the client has started.
	 *
	 * @param dest    the user to send this send to.
	 * @param message the send to send.
	 */
	public synchronized void messageOne(String dest, String message)
	{
		sendMessage(new ChatMessage(name, dest, message));
	}

	/**
	 * Asks the server to send a list of all the users.
	 */
	public synchronized void listNames()
	{
		sendMessage(new NameRequestMessage(name));
	}

	/**
	 * Deals with outgoing messages.
	 */
	private void sendMessage(IClientMessage message)
	{
		if (!running)
		{
			listener.stat("Client is not running!", true);
			return;
		}
		try
		{
			out.writeObject(message);
			out.flush();
			listener.messageSent(message);
		} catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	/**
	 * Deals with incoming messages.
	 */
	private void messageReceived(IServerMessage message)
	{
		listener.messageReceived(message);
	}
}
