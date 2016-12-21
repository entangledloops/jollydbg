import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JollyDbg extends Application
{
  public static final String VERSION = "1.0.1";

  private Thread                   gdbThread    = null;
  private TextArea                 txtAssembly  = null;
  private TableView<Register>      tblRegisters = null;
  private ObservableList<Register> registers = FXCollections.observableArrayList();

  boolean step = false;
  boolean quit = false;
  boolean ret = false;

  public void launchGdb()
  {
    gdbThread = new Thread(() ->
    {
      final List<String> gdbList = new ArrayList<>();
      gdbList.add("gdb");

      final String params = getParameters().getRaw().stream().skip(1).collect(Collectors.toList()).stream().reduce("", (s,t) -> s + " " + t);
      final List<String> cmdList = Stream.of(gdbList, getParameters().getRaw()).flatMap(Collection::stream).limit(2).collect(Collectors.toList());
      final String[] cmdArray = cmdList.toArray(new String[cmdList.size()]);

      try
      {
        final ProcessBuilder gdbBuilder = new ProcessBuilder(cmdArray);
        gdbBuilder.redirectErrorStream(true);

        final Process gdb = gdbBuilder.start();
        final BufferedReader br = new BufferedReader(new InputStreamReader( gdb.getInputStream() ));
        final BufferedWriter bw = new BufferedWriter(new OutputStreamWriter( gdb.getOutputStream() ));

        bw.write("break main\nrun " + params + "\ndisassemble\n");
        bw.flush();

        new Thread(() ->
        {
          try
          {
            boolean registerUpdate = false, registerUpdateStarted = false;
            boolean disassemblyUpdate = false;
            String input;

            while (null != (input = br.readLine()))
            {
              if (registerUpdate)
              {
                if (input.startsWith("(gdb)") || input.startsWith("ymm"))
                {
                  if (registerUpdateStarted)
                  {
                    registerUpdateStarted = false;
                    registerUpdate = false;
                    continue;
                  }
                  else
                  {
                    input = input.replace("(gdb)", "");
                    registerUpdateStarted = true;
                  }
                }
                else if (input.startsWith("End"))
                {
                  registerUpdate = false;
                  continue;
                }

                final List<String> register = Stream.of(input.replace("\t", " ").split(" ")).filter(s -> s.trim().length() != 0).collect(Collectors.toList());

                boolean standard = register.size() == 3;
                final String name = register.get(0);
                final String dec = standard ? register.get(1) : register.stream().skip(2).reduce("", (s,t) -> s + " " + t);
                final String hex = standard ? register.get(2) : "";

                Platform.runLater(() -> registers.add(new Register(name, dec, hex)));
              }
              else if (!disassemblyUpdate)
              {
                if (input.startsWith("(gdb) Dump"))
                {
                  disassemblyUpdate = true;
                  registerUpdate = false;

                  Platform.runLater(() -> txtAssembly.clear());
                }
              }
              else if (input.startsWith("End"))
              {
                disassemblyUpdate = false;
                registerUpdate = true;

                Platform.runLater(() -> { registers.clear(); tblRegisters.refresh(); });

                bw.write("info all-registers\n");
                bw.flush();
              }
              else
              {
                final String inputCopy = input;
                Platform.runLater(() -> txtAssembly.appendText(inputCopy + "\n"));
              }
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
              if (ret)
              {
                ret = false;
                bw.write("return\ny\n");
                step = true;
              }
              if (step)
              {
                step = false;
                bw.write("stepi\ndisassemble\n");
                bw.flush();
              }

              Thread.sleep(10);
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
    txtAssembly.setFont(Font.font(15));

    TableColumn regName = new TableColumn("Register");
    TableColumn regValue = new TableColumn("Value");

    TableColumn regValueHex = new TableColumn("Hex");
    regValueHex.setPrefWidth(200);

    TableColumn regValueDec = new TableColumn("Dec");
    regValueDec.setPrefWidth(200);

    regValue.getColumns().addAll(regValueHex, regValueDec);

    regName.setCellValueFactory(new PropertyValueFactory<Register,String>("name"));
    regValueHex.setCellValueFactory(new PropertyValueFactory<Register,String>("valueHex"));
    regValueDec.setCellValueFactory(new PropertyValueFactory<Register,String>("valueDec"));

    tblRegisters = new TableView();
    tblRegisters.setItems(registers);
    tblRegisters.getColumns().addAll(regName, regValue);
    tblRegisters.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

    HBox hbox = new HBox();
    hbox.setSpacing(5);
    hbox.setPadding(new Insets(10, 0, 0, 10));
    hbox.getChildren().addAll(txtAssembly, tblRegisters);
    hbox.setAlignment(Pos.TOP_CENTER);
    hbox.setHgrow(tblRegisters, Priority.ALWAYS);

    StackPane root = new StackPane();
    root.getChildren().add(hbox);

    final Scene scene = new Scene(root);
    scene.setOnKeyPressed(event ->
    {
      switch (event.getCode())
      {
        case F8:
          step = true;
          break;

        case F9:
          ret = true;
          break;

        case ESCAPE:
          quit = true;
          break;

        default:
      }
    });

    txtAssembly.setOnKeyPressed( scene.getOnKeyPressed() );
    tblRegisters.setOnKeyPressed( scene.getOnKeyPressed() );

    Screen screen = Screen.getPrimary();
    Rectangle2D bounds = screen.getVisualBounds();

    primaryStage.setX(bounds.getMinX());
    primaryStage.setY(bounds.getMinY());
    primaryStage.setWidth(bounds.getWidth());
    primaryStage.setHeight(bounds.getHeight());
    primaryStage.setScene(scene);
    primaryStage.setOnCloseRequest(e -> quit = true);
    primaryStage.show();

    launchGdb();
  }

  public static class Register
  {
    private final SimpleStringProperty name;
    private final SimpleStringProperty valueHex;
    private final SimpleStringProperty valueDec;

    private Register(String fName, String lName, String valueDec) {
      this.name = new SimpleStringProperty(fName);
      this.valueHex = new SimpleStringProperty(lName);
      this.valueDec = new SimpleStringProperty(valueDec);
    }

    public String getName() {
      return name.get();
    }
    public void setName(String fName) {
      name.set(fName);
    }

    public String getValueHex() {
      return valueHex.get();
    }
    public void setValueHex(String fName) {
      valueHex.set(fName);
    }

    public String getValueDec() {
      return valueDec.get();
    }
    public void setValueDec(String fName) {
      valueDec.set(fName);
    }
  }

  public static void main(String[] args)
  {
    launch(args);
  }
}
