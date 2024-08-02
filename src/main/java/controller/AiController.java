package controller;

import algorithm.*;
import inputOutput.ExportMeasurement;
import inputOutput.TransformationMatrix;
import inputOutput.VideoSource;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.util.Duration;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point3;
import userinterface.PlottableImage;

import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.*;
import java.util.prefs.Preferences;

public class AiController implements Controller {

    private final ImageDataManager imageDataManager = new ImageDataManager();
    private final TrackingService trackingService = TrackingService.getInstance();
    private final Map<String, Integer> deviceIdMapping = new LinkedHashMap<>();
    private static final Preferences userPreferencesGlobal = Preferences.userRoot().node("IGT_Settings");

    @FXML
    public ChoiceBox<String> sourceChoiceBox;

    @FXML
    public Label distanceLabel;
    @FXML
    public CheckBox trackingConnectedStatusBox;
    public ProgressIndicator connectionProgressSpinner;
    @FXML
    public PlottableImage videoImagePlot;
    @FXML
    public LineChart<Number, Number> lineChart;
    @FXML
    public ChoiceBox<String> ModeSelection;
    @FXML
    public TitledPane PathControlPanel;
    @FXML
    public Button clearAll;

    private Label statusLabel;
    private Timeline videoTimeline;
    private BufferedImage currentShowingImage;
    private List<ExportMeasurement> lastTrackingData = new ArrayList<>();

    // Used to crop the image to the actual content. Dirty describes whether the roi cache needs to be updated on the next transform, it's set when a new matrix is loaded
    private int[] matrixRoi = new int[4];
    private boolean roiDirty = true;

    private TransformationMatrix transformationMatrix = new TransformationMatrix();

    private final ObservableList<XYChart.Series<Number, Number>> dataSeries = FXCollections.observableArrayList();

    private final ObservableList<Point3> clicked_image_points = FXCollections.observableArrayList();
    private final ObservableList<Point3> clicked_tracker_points = FXCollections.observableArrayList();
    private Mat cachedTransformMatrix = null;

    private final XYChart.Series<Number, Number> referencePoint = new XYChart.Series<Number, Number>();
    private XYChart.Series<Number, Number> trackingPoint = new XYChart.Series<Number, Number>();
    private final ArrayList<XYChart.Series<Number, Number>> referencePointsListPath = new ArrayList<XYChart.Series<Number, Number>>();
    private final ObservableList<XYChart.Series<Number,Number>> lineDataSeries = FXCollections.observableArrayList();

    NumberAxis xAxis = new NumberAxis(-500, 500, 100);
    NumberAxis yAxis = new NumberAxis(-500, 500, 100);


    @Override
    public void initialize(URL location, ResourceBundle resources) {
        registerController();


        lastPoint = null; // Reset lastPoint
        currentLine = new XYChart.Series<>(); // Reset currentLine

        trackingService.registerObserver((sourceChanged,dataServiceChanged,timelineChanged) -> updateTrackingInformation());

        connectionProgressSpinner.setVisible(false);
        sourceChoiceBox.getSelectionModel().selectedItemProperty().addListener(x -> changeVideoView());
        sourceChoiceBox.setTooltip(new Tooltip("If you have multiple cameras connected, enable \"Search for more videos\" in the settings view to see all available devices"));

        videoImagePlot.setData(dataSeries);
        videoImagePlot.registerImageClickedHandler(this::onImageClicked);
        videoImagePlot.registerImageClickedHandler(this::onSRM);
        loadAvailableVideoDevicesAsync();

        lineChart.getXAxis().setVisible(false);
        lineChart.getYAxis().setVisible(false);
        lineChart.setLegendVisible(false);
        videoImagePlot.setLegendVisible(false);
        lineChart.setMouseTransparent(true);
        lineChart.setAnimated(false);
        lineChart.setCreateSymbols(true);
        lineChart.setAlternativeRowFillVisible(false);
        lineChart.setAlternativeColumnFillVisible(false);
        lineChart.setHorizontalGridLinesVisible(false);
        lineChart.setVerticalGridLinesVisible(false);
        lineChart.lookup(".chart-plot-background").setStyle("-fx-background-color: transparent;");
        lineChart.setData(lineDataSeries);

        ModeSelection.setValue("Single Point Mode");
        dataSeries.add(referencePoint);

        //State machine for the choice box for point mode selection
        ModeSelection.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            dataSeries.clear();
            if (Objects.equals(newValue, "Single Point Mode")) {
                dataSeries.add(referencePoint);
                PathControlPanel.setVisible(false);
                lineDataSeries.clear();
                trackingPoint.setData(referencePoint.getData());
            }
            if (Objects.equals(newValue, "Path Mode")) {
                dataSeries.addAll(referencePointsListPath);
                PathControlPanel.setVisible(true);
                redrawPointsPathMode();
                trackingPoint = referencePointsListPath.get(0);
            }

            System.out.println("Selected item: " + newValue );
            // Add your custom code here...
        });

        clearAll.setOnAction((event) -> {
            dataSeries.clear();
            referencePointsListPath.clear();
            lineDataSeries.clear();
        });
    }
    private XYChart.Data<Number, Number> lastPoint = null;
    private XYChart.Data<Number, Number> tempo = null;
    private double tempX;
    private double tempY;


    private XYChart.Series<Number, Number> currentLine = new XYChart.Series<>();

    /**
     * Calculates the Euclidean distance between two points.
     * @param point1 The tracking point.
     * @param point2 The reference point.
     * @return The distance between the two points.
     */
    public double calculateDistance(XYChart.Data<Number, Number> point1, XYChart.Data<Number, Number> point2) {

        double x1 = point1.getXValue().doubleValue();
        System.out.println("p1: "+ x1);
        double y1 = point1.getYValue().doubleValue();
        System.out.println("p1: "+ y1);
        double x2 = point2.getXValue().doubleValue();
        System.out.println("p2: "+ x2);
        double y2 = point2.getYValue().doubleValue();
        System.out.println("p2: "+ y2);

        // Calculate the Euclidean distance
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }



    private void onSRM(double v, double v1) {
        switch (ModeSelection.getValue()) {
            case "Single Point Mode":
                System.out.println("Set reference point to " + v + " " + v1);

                // Create a new point
                XYChart.Data<Number, Number> newPoint = new XYChart.Data<>(v, v1);

                // If there is a previous point, update the line
                if (lastPoint != null) {
                    // Clear previous line data
                    currentLine.getData().clear();
                }

                // Add new point
                currentLine.getData().add(newPoint);

                // Update the chart with the new line
                if (!lineDataSeries.contains(currentLine)) {
                    lineDataSeries.add(currentLine);
                }

                // Update the reference point to the new point
                referencePoint.setData(FXCollections.observableArrayList(newPoint));

                // Update the last point
                lastPoint = newPoint;

                // Update the tracking point
                trackingPoint.setData(FXCollections.observableArrayList(newPoint));

                tempo = new XYChart.Data<>(tempX,tempY);
                double distance = calculateDistance(tempo, newPoint);
                distanceLabel.setText("distance: " + String.format("%.2f", distance));
                break;
            case "Path Mode":
                referencePointsListPath.add(new XYChart.Series<>("point",FXCollections.observableArrayList(new XYChart.Data<>(v,v1))));
                dataSeries.add(referencePointsListPath.get(referencePointsListPath.size()-1));
                connectPointsPathMode();
                trackingPoint = new XYChart.Series<>("hi",FXCollections.observableArrayList(new XYChart.Data<>(referencePointsListPath.get(0).getData().get(0).getXValue(),referencePointsListPath.get(0).getData().get(0).getXValue())));

                break;
        }

    }

    private void connectPointsPathMode(){
        if(referencePointsListPath.size() == 1) {
            return;
        }
        int i = referencePointsListPath.size() - 2;

            XYChart.Series<Number, Number> series = referencePointsListPath.get(i);
            XYChart.Series<Number, Number> nextSeries = referencePointsListPath.get(i + 1);

            // Get the last point of the current series and the first point of the next series
            XYChart.Data<Number, Number> lastPoint = series.getData().get(series.getData().size() - 1);
            XYChart.Data<Number, Number> firstPointNext = nextSeries.getData().get(0);

            // Create a new series to represent the line between the two points
            XYChart.Series<Number, Number> lineSeries = new XYChart.Series<>();
            lineSeries.getData().add(new XYChart.Data<>(lastPoint.getXValue(), lastPoint.getYValue()));
            lineSeries.getData().add(new XYChart.Data<>(firstPointNext.getXValue(), firstPointNext.getYValue()));

            // Add the line series to the line chart
            lineDataSeries.add(lineSeries);

    }
    private void redrawPointsPathMode(){
        for (int i = 0; i < referencePointsListPath.size() - 1; i++) {
            XYChart.Series<Number, Number> series = referencePointsListPath.get(i);
            XYChart.Series<Number, Number> nextSeries = referencePointsListPath.get(i + 1);

            // Get the last point of the current series and the first point of the next series
            XYChart.Data<Number, Number> lastPoint = series.getData().get(series.getData().size() - 1);
            XYChart.Data<Number, Number> firstPointNext = nextSeries.getData().get(0);

            // Create a new series to represent the line between the two points
            XYChart.Series<Number, Number> lineSeries = new XYChart.Series<>();
            lineSeries.getData().add(new XYChart.Data<>(lastPoint.getXValue(), lastPoint.getYValue()));
            lineSeries.getData().add(new XYChart.Data<>(firstPointNext.getXValue(), firstPointNext.getYValue()));

            // Add the line series to the line chart
            lineDataSeries.add(lineSeries);
        }
    }

    @Override
    public void close() {
        unregisterController();
        if (videoTimeline != null) {
            videoTimeline.stop();
        }
        imageDataManager.closeConnection();
    }

    /**
     * Enables the Main View to inject the tracking data controller
     *
     */
    public void updateTrackingInformation() {
        var selected = trackingService.getTrackingDataSource() != null && trackingService.getTimeline() != null;
        trackingConnectedStatusBox.setSelected(selected);
    }

    /**
     * Enables the Main View to inject the status label at the bottom of the window
     *
     * @param statusLabel The injected label
     */
    public void setStatusLabel(Label statusLabel) {
        this.statusLabel = statusLabel;
        this.statusLabel.setText("");
    }


    /**
     * Initializes the loading of available video devices. This is done asynchronously.
     * While loading, the connection spinner shows.
     */
    private void loadAvailableVideoDevicesAsync() {
        connectionProgressSpinner.setVisible(true);
        new Thread(() -> {
            createDeviceIdMapping(userPreferencesGlobal.getBoolean("searchForMoreVideos", false));
            Platform.runLater(() -> {
                sourceChoiceBox.getItems().addAll(deviceIdMapping.keySet());
                if (!deviceIdMapping.isEmpty()) {
                    sourceChoiceBox.getSelectionModel().select(0);
                } else {
                    statusLabel.setText("No video devices found!");
                }
                connectionProgressSpinner.setVisible(false);
            });
        }).start();
    }

    /**
     * Tests out available video device ids. All devices that don't throw an error are added to the list.
     * This is bad style, but openCV does not offer to list available devices.
     * @param exhaustiveSearch Whether all available devices shall be enumerated. If set to false, there's a minimal performance gain.
     */
    private void createDeviceIdMapping(boolean exhaustiveSearch) {
        if(!exhaustiveSearch){
            deviceIdMapping.put("Default Camera",0);
            return;
        }

        int currentDevice = 0;
        boolean deviceExists = imageDataManager.openConnection(VideoSource.LIVESTREAM, currentDevice);
        imageDataManager.closeConnection();
        while (deviceExists) {
            deviceIdMapping.put("Camera " + currentDevice, currentDevice);
            currentDevice++;
            deviceExists = imageDataManager.openConnection(VideoSource.LIVESTREAM, currentDevice);
            imageDataManager.closeConnection();
        }
    }

    /**
     * Changes the input stream for the video view. Also starts the timeline to update the current image.
     */
    private void changeVideoView() {
        if (!sourceChoiceBox.getSelectionModel().isEmpty()) {
            var selectedItem = sourceChoiceBox.getSelectionModel().getSelectedItem();
            int deviceId = deviceIdMapping.get(selectedItem);
            imageDataManager.closeConnection();
            imageDataManager.openConnection(VideoSource.LIVESTREAM, 1);
            if (videoTimeline == null) {
                videoTimeline = new Timeline();
                videoTimeline.setCycleCount(Animation.INDEFINITE);
                videoTimeline.getKeyFrames().add(
                        new KeyFrame(Duration.millis(100)
                                , event -> this.updateVideoImage())
                );
                videoTimeline.play();
            }
        } else {
            videoTimeline.stop();
            videoTimeline = null;
        }
    }

    /**
     * Loads and displays the next image from the stream.
     * If a measurement is scheduled, it queries the current tracking data and saves the data.
     */
    private void updateVideoImage() {
        var matrix = imageDataManager.readMat();
        if(matrix != null && !matrix.empty()) {
            // Currently, we don't do image transformations, only tracking transformations
            // matrix = applyImageTransformations(matrix);
            currentShowingImage = ImageDataProcessor.Mat2BufferedImage(matrix);
        }

        // Show Tracking Data
        if(trackingConnectedStatusBox.isSelected()){
            updateTrackingData();
        }

        if(matrix != null && !matrix.empty()) {
            videoImagePlot.setImage(ImageDataProcessor.Mat2Image(matrix, ".png"));
            ((NumberAxis) lineChart.getXAxis()).setAutoRanging(false);
            ((NumberAxis) lineChart.getXAxis()).setLowerBound(((NumberAxis) videoImagePlot.getXAxis()).getLowerBound());
            ((NumberAxis) lineChart.getXAxis()).setUpperBound(((NumberAxis) videoImagePlot.getXAxis()).getUpperBound());
            ((NumberAxis) lineChart.getXAxis()).setTickUnit(((NumberAxis) videoImagePlot.getXAxis()).getTickUnit());
            ((NumberAxis) lineChart.getYAxis()).setAutoRanging(false);
            ((NumberAxis) lineChart.getYAxis()).setLowerBound(((NumberAxis) videoImagePlot.getYAxis()).getLowerBound());
            ((NumberAxis) lineChart.getYAxis()).setUpperBound(((NumberAxis) videoImagePlot.getYAxis()).getUpperBound());
            ((NumberAxis) lineChart.getYAxis()).setTickUnit(((NumberAxis) videoImagePlot.getYAxis()).getTickUnit());

            lineChart.prefWidthProperty().bind(videoImagePlot.widthProperty());
            lineChart.prefHeightProperty().bind(videoImagePlot.heightProperty());
            lineChart.minWidthProperty().bind(videoImagePlot.widthProperty());
            lineChart.minHeightProperty().bind(videoImagePlot.heightProperty());
            lineChart.maxWidthProperty().bind(videoImagePlot.widthProperty());
            lineChart.maxHeightProperty().bind(videoImagePlot.heightProperty());
        }
    }

    /**
     * Loads the next tracking data point and displays it on the image-plot
     */
    private void updateTrackingData() {
        // Get tracking data source and service
        var source = trackingService.getTrackingDataSource();
        var service = trackingService.getDataService();

        // Early exit if either source or service is null
        if (source == null || service == null) {
            return;
        }

        // Update the source
        source.update();

        // Load next data from the service
        List<Tool> tools = service.loadNextData(1);

        // Early exit if no tools are available
        if (tools.isEmpty()) {
            return;
        }

        // Clear the previous tracking data
        lastTrackingData.clear();

        // Iterate through tools
        for (int i = 0; i < tools.size(); i++) {
            Tool tool = tools.get(i);

            // Ensure dataSeries and lineDataSeries lists are properly initialized
            if (dataSeries.size() <= i) {
                // Initialize missing series
                while (dataSeries.size() <= i) {
                    var series = new XYChart.Series<Number, Number>();
                    series.setName("Tool " + (dataSeries.size() + 1));  // Default name if not set
                    series.getData().add(new XYChart.Data<>(0, 0));  // Workaround to display legend
                    dataSeries.add(series);
                    series.getData().remove(0);  // Remove workaround point
                }
            }

            if (lineDataSeries.size() <= i) {
                // Initialize missing line series
                while (lineDataSeries.size() <= i) {
                    var lineSeries = new XYChart.Series<Number, Number>();
                    lineDataSeries.add(lineSeries);
                    lineSeries.getData().clear();  // Ensure line data is cleared
                }
            }

            var series = dataSeries.get(i);
            var lineSeries = lineDataSeries.get(i);

            // Get measurements and point
            var measurements = tool.getMeasurement();
            if (measurements.isEmpty()) {
                continue;  // Skip if no measurements available
            }

            var point = measurements.get(measurements.size() - 1).getPos();
            tempX = point.getX();
            tempY = point.getY();

            // Transform point and normalize coordinates
            var shifted_points = applyTrackingTransformation2d(point.getX(), point.getY(), point.getZ());
            var x_normalized = shifted_points[0] / (currentShowingImage != null ? currentShowingImage.getWidth() : 1); // Avoid division by zero
            var y_normalized = shifted_points[1] / (currentShowingImage != null ? currentShowingImage.getHeight() : 1); // Avoid division by zero

            // Update last tracking data
            lastTrackingData.add(new ExportMeasurement(
                    tool.getName(),
                    point.getX(),
                    point.getY(),
                    point.getZ(),
                    shifted_points[0],
                    shifted_points[1],
                    shifted_points[2],
                    x_normalized,
                    y_normalized
            ));

            // Update series data
            ObservableList<XYChart.Data<Number, Number>> data = series.getData();
            ObservableList<XYChart.Data<Number, Number>> lineData = FXCollections.observableArrayList();

            data.add(new XYChart.Data<>(shifted_points[0], shifted_points[1]));
            lineData.add(new XYChart.Data<>(shifted_points[0], shifted_points[1]));
            lineData.add(new XYChart.Data<>(trackingPoint.getData().isEmpty() ? shifted_points[0] : trackingPoint.getData().get(0).getXValue(),
                    trackingPoint.getData().isEmpty() ? shifted_points[1] : trackingPoint.getData().get(0).getYValue()));
            if (!trackingPoint.getData().isEmpty()) {
                XYChart.Data<Number, Number> lastTrackingPoint = trackingPoint.getData().get(trackingPoint.getData().size() - 1);

                // Calculate distance between the last tracking point and the current shifted point
                double distance = calculateDistance(new XYChart.Data<>(shifted_points[0], shifted_points[1]), lastTrackingPoint);
                distanceLabel.setText("Distance: " + String.format("%.2f", distance));
            }

            lineSeries.setData(lineData);

            // Maintain a maximum number of points in the series
            final int max_num_points = 6; // Can be adjusted as needed
            if (data.size() > max_num_points) {
                data.remove(0);
            }
            if (lineData.size() > max_num_points) {
                lineData.remove(0);
            }
        }
    }


    /**
     * Applies the transformation matrix to the image
     * @param mat The image to be transformed
     * @return The transformed image
     */
    private Mat applyImageTransformations(Mat mat){
//        Imgproc.warpAffine(mat, mat, transformationMatrix.getTranslationMat(), mat.size());
//        Imgproc.warpAffine(mat, mat, transformationMatrix.getRotationMat(), mat.size());
//        Imgproc.warpAffine(mat, mat, transformationMatrix.getScaleMat(), mat.size());

        /*
        var imagePoints = transformationMatrix.getImagePoints();
        var trackingPoints = transformationMatrix.getTrackingPoints();
        var outMat = new Mat();
        if(!imagePoints.empty() && !trackingPoints.empty()) {
            //Mat srcPoints = Converters.vector_Point_to_Mat(imagePoints, CvType.CV_64F);
            //Mat dstPoints = Converters.vector_Point_to_Mat(trackingPoints, CvType.CV_64F);

            var matrix = Imgproc.getPerspectiveTransform(imagePoints, trackingPoints);
            //var matrix = Calib3d.findHomography(imagePoints, trackingPoints, Calib3d.RANSAC);

            Imgproc.warpPerspective(mat, outMat, matrix, new Size());
            mat = outMat;
            mat = outMat;
            Imgproc.warpAffine(mat, outMat, transformationMatrix.getScaleMat(), mat.size());
            mat = outMat;
            Imgproc.warpAffine(mat, outMat, transformationMatrix.getTranslationMat(), mat.size());
        }
        */

        if(roiDirty){
            // Set noExecute to false if the Roi should be calculated (and thus, the image cropped)
            matrixRoi = MatHelper.calculateRoi(mat, true);
            roiDirty = false;
        }
        mat = mat.submat(matrixRoi[0],matrixRoi[1], matrixRoi[2],matrixRoi[3]);
        return mat;
    }

    /**
     * Applies the (2D) transformation on a tracking point
     * @param x X-Coordinate of the point
     * @param y Y-Coordinate of the point
     * @param z Z-Coordinate of the point - Ignored in the 2d version
     * @return The transformed point as array of length 3 (xyz)
     */
    private double[] applyTrackingTransformation2d(double x, double y, double z) {
        // TODO: Cache matrix
        if (cachedTransformMatrix == null){
            cachedTransformMatrix = transformationMatrix.getTransformMatOpenCvEstimated2d();
        }
        var vector = new Mat(3,1, CvType.CV_64F);

        if(userPreferencesGlobal.getBoolean("verticalFieldGenerator", false)){
            vector.put(0,0,z);
        }else{
            vector.put(0,0,x);
        }
        vector.put(1,0,y);
        vector.put(2,0,1);

        var pos_star = new Mat(2,1,CvType.CV_64F);
        Core.gemm(cachedTransformMatrix, vector,1, new Mat(),1,pos_star);
        double[] out = new double[3];
        out[0] = pos_star.get(0,0)[0];
        out[1] = pos_star.get(1,0)[0];
        out[2] = 0;
        return out;
    }

    /**
     * Called, when the user clicks on the live image. Used to get landmarks for transformation. Uses 0 for the image plane as default
     * @param x X-Coordinate in the image
     * @param y Y-Coordinate in the image
     */
    private void onImageClicked(double x, double y){
        System.out.println("clicked on image");
        var trackingData = lastTrackingData;
        if(trackingData.size() > 0 && clicked_image_points.size() < 4) {
            // We also directly save the tracking-coordinates at this point.
            System.out.println("Image: (" + String.format(Locale.ENGLISH, "%.2f", x) + "," + String.format(Locale.ENGLISH, "%.2f", y) + ",0)\nImage (Relative): (" + String.format(Locale.ENGLISH, "%.2f", x) + "," + String.format(Locale.ENGLISH, "%.2f", (currentShowingImage.getHeight() - y)) + ",0)");
            for (var measurement : trackingData) {
                System.out.println("Tracker " + measurement.toolName + ": (" + String.format(Locale.ENGLISH, "%.2f", measurement.x_raw) + "," + String.format(Locale.ENGLISH, "%.2f", measurement.y_raw) + "," + String.format(Locale.ENGLISH, "%.2f", measurement.z_raw) + ")\n");
            }

            clicked_image_points.add(new Point3(x, y, 0.0));
            if(userPreferencesGlobal.getBoolean("verticalFieldGenerator", false)) {
                clicked_tracker_points.add(new Point3(trackingData.get(0).z_raw, trackingData.get(0).y_raw, trackingData.get(0).x_raw));
            }else {
                clicked_tracker_points.add(new Point3(trackingData.get(0).x_raw, trackingData.get(0).y_raw, trackingData.get(0).z_raw));
            }

        }
    }
}
