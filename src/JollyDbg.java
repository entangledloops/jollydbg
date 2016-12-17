import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JollyDbg extends Application
{
  public static final String VERSION = "1.0.0";
  public static final int DEFAULT_WIDTH = 1366;
  public static final int DEFAULT_HEIGHT = 768;

  private Thread gdbThread = null;
  private TextArea txtAssembly = null;
  boolean step = false;
  boolean quit = false;

  public void launchGdb()
  {
    gdbThread = new Thread(() ->
    {
      final List<String> gdbList = new ArrayList<>();
      gdbList.add("gdb");

      final List<String> cmdList = Stream.of(gdbList, getParameters().getRaw()).flatMap(Collection::stream).collect(Collectors.toList());
      final String[] cmdArray = cmdList.toArray(new String[cmdList.size()]);

      try
      {
        final ProcessBuilder gdbBuilder = new ProcessBuilder(cmdArray);
        gdbBuilder.redirectErrorStream(true);

        final Process gdb = gdbBuilder.start();
        final BufferedReader br = new BufferedReader(new InputStreamReader( gdb.getInputStream() ));
        final BufferedWriter bw = new BufferedWriter(new OutputStreamWriter( gdb.getOutputStream() ));

        bw.write("break main\nr\ndisassemble\n");
        bw.flush();

        new Thread(() ->
        {
          try
          {
            boolean disassembly = false;
            String input;
            while (null != (input = br.readLine()))
            {
              if (!disassembly)
              {
                if (input.startsWith("(gdb) Dump"))
                {
                  disassembly = true;
                  Platform.runLater(() -> txtAssembly.clear());
                }
                continue;
              }
              else if (input.startsWith("End"))
              {
                disassembly = false;
                continue;
              }

              final String inputCopy = input;
              Platform.runLater(() -> txtAssembly.appendText(inputCopy + "\n"));
            }
          }
          catch (Throwable ignored) {}
        }).start();

        new Thread(() ->
        {
          try
          {
            while (true)
            {
              if (quit)
              {
                bw.write("quit\ny\n");
                bw.flush();
                Platform.exit();
              }
              if (step)
              {
                step = false;
                bw.write("step\ndisassemble\n");
                bw.flush();
              }

              Thread.sleep(50);
            }
          }
          catch (Throwable ignore) {}
        }).start();

        //bw.close();
        //br.close();
        //gdb.destroy();
      }
      catch (Throwable t)
      {
        t.printStackTrace();
        Platform.exit();
      }
    });
    gdbThread.start();
  }


  @Override
  public void start(Stage primaryStage)
  {
    primaryStage.setTitle("JollyDbg - " + VERSION);

    txtAssembly = new TextArea();
    txtAssembly.setEditable(false);

    StackPane root = new StackPane();
    root.getChildren().add(txtAssembly);

    final Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    scene.setOnKeyPressed(event ->
    {
      switch (event.getCode())
      {
        case F8:
          step = true;
          break;

        case ESCAPE:
          quit = true;
          break;

        default:
      }
    });

    txtAssembly.setOnKeyPressed( scene.getOnKeyPressed() );

    primaryStage.setScene(scene);
    primaryStage.show();

    launchGdb();
  }

  public static void main(String[] args)
  {
    launch(args);
  }
}
