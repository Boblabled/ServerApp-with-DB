import Commands.CommandExecution;
import DateBase.DateBase;
import Serialization.Serialization;
import Elements.MusicBand;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Сервер
 */
public class Server extends RecursiveTask<String> {

    private static Socket clientSocket; // сокет для общения
    private static ServerSocket server; // серверсокет
    private static BufferedReader in; // поток чтения из сокета
    private static BufferedWriter out; // поток записи в сокет
    private static String clientLogin; // имя пользователя
    private static final LinkedHashSet<MusicBand> collection = new LinkedHashSet<MusicBand>(); // коллекция
    private static final int port = 4004; // порт для подключения
    private static final Serialization serialization = new Serialization(); // сериализатор/десериализатор
    private static final LocalDateTime today = LocalDateTime.now(); //
    private static final String temp = System.getenv().get("MusicBandPATH3"); // переменная окружения
    private static final String serializedDate = "serializedDate.txt"; // файл для передачи сериализованных сообщений
    private static final Logger logger = LogManager.getLogger(); // логгер


    /**
     * Это main)
     */
    public static void main(String[] args) {
        try {
            ForkJoinPool commonPool = new ForkJoinPool(4);
            logger.info("Сервер запущен!");
            DateBase.connect(collection);
            while (true) {
                connection(true, "non");
                authorisation();
                while (true) {
                    String message = commonPool.invoke(new Server());
                    if (message != null) {
                        write(message);
                        TimeUnit.MILLISECONDS.sleep(100);
                        if (message.equals("exit")) {
                            connection(false, "non");
                            break;
                        } else if (message.equals("close server")) {
                            connection(false,"close server");
                            break;
                        }
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            System.err.println(e);
        }
    }

    /**
     * Модуль выполнения команд
     *
     * @param message - сообщение принятое от клиента
     * @param today - текущая дата
     */
    public static String execution(String message, LocalDateTime today) throws IOException, ExecutionException, InterruptedException {
        ExecutorService service = Executors.newCachedThreadPool();
        ReadWriteLock lock = new ReentrantReadWriteLock();
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        Future<Object> outPut = service.submit(() -> {
            String command;
            String[] field;
            field = message.split(" ");
            command = field[0];
            Object result;
            lock.writeLock().lock();
            try {
                result = CommandExecution.action(collection, message, command, today, clientLogin);
            } finally {
                lock.writeLock().unlock();
            }
            return result;
        });
        return (String) outPut.get();
    }

    /**
     * Модуль приёма подключений
     *
     * @param connect - режим работы (отключиться/подключиться)
     * @param close - звкрытие сервера
     * @throws IOException - ошибка подключения
     */
    public static void connection(boolean connect, String close) throws IOException {
        if (connect) {
            server = new ServerSocket(port);
            logger.info("Ожидание подключения...");
            clientSocket = server.accept();
            logger.info("Соединение с клиентом установлено");
        }
        if (!connect) {
            logger.info("Соединение с клиентом разорвано");
            clientSocket.close();
            server.close();
            if (close.equals("close server")){
                DateBase.disconnect();
                logger.info("Сервер закрыт!");
                System.exit(0);
            }
        }
    }

    /**
     * Модуль чтения запроса
     *
     * @return - возвращает десериализованную команду
     * @throws IOException - ошибка чтения запроса
     */
    public static String read() throws IOException {
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        String message = in.readLine();
        message = serialization.DeserializeObject(temp, serializedDate);
        return message;
    }

    /**
     * Модуль отправки ответов клиенту
     *
     * @param message - сообщение от клиента
     * @throws IOException - ошибка чтения запроса
     */
    public static void write(String message) throws IOException {
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        Runnable task = () -> {
            try {
                logger.info("Пользватель " + clientLogin + " ввёл: " + message);
                String messageToClient = "\nСервер принял команду: " + message + "\n" + execution(message, today);
                serialization.SerializeObject(messageToClient, temp, serializedDate);
                out.write("\n");
                out.flush();
            } catch (ExecutionException | IOException | InterruptedException ignored) {
                logger.error("Ошибка отправки ответа");
            }
        };
        Thread thread = new Thread(task);
        thread.start();
    }

    /**
     * Модуль отправки ответов клиенту
     *
     * @param work - режим работы
     * @throws IOException - ошибка чтения запроса
     */
    public static void write(boolean work) throws IOException {
        in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        out = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()));
        Runnable task = () -> {
            try {
                String messageToClient = "";
                if (work) messageToClient = "\nВы успешно авторизировались";
                else messageToClient = "\nВо время авторизации произошла ошибка, повторите попытку";
                serialization.SerializeObject(messageToClient, temp, serializedDate);
                out.write("\n");
                out.flush();
            } catch (IOException e) {
                logger.error("Ошибка отправки ответа");
            }
        };
        Thread thread = new Thread(task);
        thread.start();
    }

    /**
     * Модуль авторизации
     */
    public static void authorisation() {
        while (true) {
            try {
                String[] fields = read().split(" ");
                boolean work = false;
                if (fields.length == 3){
                    clientLogin = fields[0];
                    if (fields[2].equals("2")) work = DateBase.addUser(fields[0], fields[1]);
                    else if (fields[2].equals("1")) work = DateBase.login(fields[0], fields[1]);
                }
                write(work);
                if (work) break;
            } catch (IOException e) {
                logger.error("Ошибка авторизации");
            }
        }
    }


    /**
     * Многопоточное чтение зпросов
     *
     * @return - сообщение от пользователя
     */
    @Override
    protected String compute() {
        String message = null;
        try {
            message = read();
        } catch (IOException e) {
            logger.error("Невозможно считать запрос");
        }
        return message;
    }
}
