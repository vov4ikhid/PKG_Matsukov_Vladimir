module com.example.colorconverter {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.example.colorconverter to javafx.fxml;
    exports com.example.colorconverter;
}