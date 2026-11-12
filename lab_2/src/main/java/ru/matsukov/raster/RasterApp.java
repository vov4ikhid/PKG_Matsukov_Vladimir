package ru.matsukov.raster;

import javafx.animation.*;
import javafx.application.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.*;
import javafx.collections.transformation.SortedList;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

public final class RasterApp extends Application {
    private final ObservableList<ImageInfo> items = FXCollections.observableArrayList();
    private final TableView<ImageInfo> table = new TableView<>();
    private final Label status = new Label("Выберите файлы или папку");
    private final ProgressBar progress = new ProgressBar(0);
    private final Button files = new Button("Выбрать файлы"), folder = new Button("Выбрать папку"), cancel = new Button("Остановить"), export = new Button("Экспорт CSV");
    private ScanSession session;
    private Timeline timer;
    private int okCount;
    private boolean exporting;
    private Stage stage;

    @Override public void start(Stage stage) {
        this.stage = stage;
        cancel.setDisable(true);
        export.setDisable(true);
        HBox buttons = new HBox(8, files, folder, cancel, export);
        buttons.setPadding(new Insets(10));
        progress.setMaxWidth(Double.MAX_VALUE);
        status.setWrapText(true);
        VBox bottom = new VBox(6, progress, status);
        bottom.setPadding(new Insets(10));

        column("Имя файла", ImageInfo::name, 210);
        column("Формат", r -> r.format, 80);
        column("Размер, px", ImageInfo::dimensions, 110);
        column("DPI (X × Y)", ImageInfo::dpi, 140);
        column("Бит/пиксель", ImageInfo::depth, 100);
        column("Сжатие", r -> r.compression, 150);
        column("Статус", r -> r.status, 160);
        SortedList<ImageInfo> sorted = new SortedList<>(items); sorted.comparatorProperty().bind(table.comparatorProperty()); table.setItems(sorted);
        table.setPlaceholder(new Label("Результаты появятся после выбора файлов или папки."));
        table.setFixedCellSize(36);
        table.setRowFactory(t -> {
            TableRow<ImageInfo> row = new TableRow<>() {
                @Override protected void updateItem(ImageInfo info, boolean empty) {
                    super.updateItem(info, empty);
                    setTooltip(empty || info == null ? null : new Tooltip(info.path.toString()));
                }
            };
            row.setOnMouseClicked(e -> { if (e.getClickCount() == 2 && !row.isEmpty()) showDetail(row.getItem()); });
            return row;
        });
        table.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER && table.getSelectionModel().getSelectedItem() != null) showDetail(table.getSelectionModel().getSelectedItem()); });
        BorderPane root = new BorderPane(table, buttons, null, bottom, null);
        BorderPane.setMargin(table, new Insets(0, 10, 0, 10));
        stage.setTitle("Лабораторная работа №2 — информация об изображениях");
        stage.setMinWidth(800);
        stage.setMinHeight(400);
        stage.setScene(new Scene(root, 1100, 550));
        stage.show();

        files.setOnAction(e -> { FileChooser chooser = new FileChooser(); chooser.setTitle("Выберите файлы (формат определяется по сигнатуре)"); List<File> selected = chooser.showOpenMultipleDialog(stage); if (selected != null) begin(selected.stream().map(File::toPath).toList()); });
        folder.setOnAction(e -> { DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Папка с изображениями и подпапками"); File selected = chooser.showDialog(stage); if (selected != null) begin(List.of(selected.toPath())); });
        cancel.setOnAction(e -> { if (session != null) { session.cancel(); cancel.setDisable(true); status.setText("Остановка…"); } });
        export.setOnAction(e -> exportCsv());
        timer = new Timeline(new KeyFrame(Duration.millis(150), e -> update())); timer.setCycleCount(Animation.INDEFINITE); timer.play();
    }
    private void column(String title, Function<ImageInfo,String> getter, int width) {
        TableColumn<ImageInfo,String> column = new TableColumn<>(title); column.setPrefWidth(width);
        column.setCellValueFactory(c -> new ReadOnlyStringWrapper(getter.apply(c.getValue()))); table.getColumns().add(column);
    }
    private void begin(List<Path> paths) {
        if (session != null || paths.isEmpty()) return;
        items.clear(); table.getSortOrder().clear(); okCount = 0;
        session = new ScanSession(paths, Math.min(8, Runtime.getRuntime().availableProcessors()));
        files.setDisable(true); folder.setDisable(true); cancel.setDisable(false);
        session.start(); update();
    }
    private void update() {
        if (session == null) return;
        List<ImageInfo> batch = new ArrayList<>(); session.results.drainTo(batch, 1500);
        for (ImageInfo info : batch) { if (info.status.equals("OK")) okCount++; }
        items.addAll(batch);
        progress.setProgress(session.phase.startsWith("Поиск") ? -1 : session.discovered.get() == 0 ? 0 : items.size() / (double)session.discovered.get());
        status.setText(String.format(Locale.ROOT,
            "%s | Обработано: %d / %d | Ошибок: %d | Время: %.1f с",
            session.phase, items.size(), session.discovered.get(), items.size() - okCount, session.seconds()));
        export.setDisable(items.isEmpty() || exporting);
        if (session.finished.get() && session.results.isEmpty()) {
            if (!session.warning.isEmpty()) status.setText(status.getText() + "  •  " + session.warning);
            session = null; files.setDisable(false); folder.setDisable(false); cancel.setDisable(true);
        }
    }
    private void showDetail(ImageInfo info) { textWindow(info.name(), info.description()); }
    private void textWindow(String title, String text) {
        TextArea area = new TextArea(text); area.setEditable(false); area.setWrapText(true);
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle(title); dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().setPrefSize(740, 480); dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.setResizable(true); dialog.show();
    }
    private void exportCsv() {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Экспорт таблицы"); chooser.setInitialFileName("raster-lab.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv")); File file = chooser.showSaveDialog(stage); if (file == null) return;
        List<ImageInfo> snapshot = List.copyOf(table.getItems()); exporting = true; export.setDisable(true);
        Thread thread = new Thread(() -> {
            String message;
            try { CsvExporter.write(file.toPath(), snapshot); message = "Сохранено строк: " + snapshot.size() + "\n" + file; }
            catch (Exception e) { message = "Ошибка экспорта: " + e.getMessage(); }
            String result = message;
            Platform.runLater(() -> { exporting = false; export.setDisable(items.isEmpty()); alert("Экспорт CSV", result); });
        }, "csv-export"); thread.setDaemon(true); thread.start();
    }
    private void alert(String title, String message) { Alert a = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK); a.initOwner(stage); a.setTitle(title); a.setHeaderText(null); a.show(); }
    @Override public void stop() { if (session != null) session.cancel(); if (timer != null) timer.stop(); }
    public static void main(String[] args) { launch(args); }
}
