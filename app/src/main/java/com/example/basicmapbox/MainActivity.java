package com.example.basicmapbox;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Bundle;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import com.google.gson.JsonObject;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.LineString;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.maps.Style;
import com.mapbox.mapboxsdk.style.layers.LineLayer;
import com.mapbox.mapboxsdk.style.layers.Property;
import com.mapbox.mapboxsdk.style.layers.PropertyFactory;
import com.mapbox.mapboxsdk.style.layers.SymbolLayer;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;


import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconOffset;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconSize;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineCap;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineColor;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineJoin;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineWidth;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.ably.lib.realtime.AblyRealtime;
import io.ably.lib.realtime.Channel;
import io.ably.lib.types.AblyException;

import static androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE;
import static com.mapbox.mapboxsdk.style.expressions.Expression.eq;
import static com.mapbox.mapboxsdk.style.expressions.Expression.get;
import static com.mapbox.mapboxsdk.style.expressions.Expression.literal;
import static com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAnchor;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback,
        MapboxMap.OnMapClickListener {

    private static final String BUS_LAYER_ID = "bus_layer";
    private static final String CALLOUT_LAYER_ID = "callout_layer";
    private static final String LINE_LAYER_ID = "line_layer";
    private static final String LINE_ID = "line_id";
    private static final String ICON_ID = "icon_id";
    private static final String BUS_SELECTED = "selected";
    private static final long CAMERA_ANIMATION_TIME = 1950;
    private MapView mapView;
    private MapboxMap mapboxMap;
    private RecyclerView recyclerView;

    private GeoJsonSource source;
    private FeatureCollection featureCollection = FeatureCollection.fromFeatures(new ArrayList());
    private HashMap<String, Feature> buses = new HashMap<>();
    private HashMap<String, Integer> currentBusStep = new HashMap<>();
    private HashMap<String, Animator> currentAnimator = new HashMap<>();
    private HashMap<String, Boolean> busIsMoving = new HashMap<>();
    private HashMap<String, List<Point>> busPoints = new HashMap<>();

    private AnimatorSet animatorSet;
    private GeoJsonSource lineSource;
    private List<Point> markerLinePointList = new ArrayList<>();


    @ActivityStep
    private int currentStep;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef( {STEP_INITIAL, STEP_LOADING, STEP_READY})
    public @interface ActivityStep {
    }

    private static final int STEP_INITIAL = 0;
    private static final int STEP_LOADING = 1;
    private static final int STEP_READY = 2;

    private static final Map<Integer, Double> stepZoomMap = new HashMap<>();

    static {
        stepZoomMap.put(STEP_INITIAL, 11.0);
        stepZoomMap.put(STEP_LOADING, 13.5);
        stepZoomMap.put(STEP_READY, 18.0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Mapbox access token is configured here. This needs to be called either in your application
        // object or in the same activity which contains the mapview.
        Mapbox.getInstance(this, getString(R.string.access_token));

        // This contains the MapView in XML and needs to be called after the access token is configured.
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.rv_on_top_of_map);

        // Initialize the map view
        mapView = findViewById(R.id.mapView);
        mapView.onCreate(savedInstanceState);
        mapView.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull final MapboxMap mapboxMap) {
        this.mapboxMap = mapboxMap;
        mapboxMap.setStyle(new Style.Builder().fromUri("mapbox://styles/mapbox/cjf4m44iw0uza2spb3q0a7s41")
                        .withImage(ICON_ID, BitmapFactory.decodeResource(MainActivity.this.getResources(), R.drawable.ic_bus2))
                , style -> {
                    mapboxMap.getUiSettings().setCompassEnabled(false);
                    mapboxMap.getUiSettings().setLogoEnabled(false);
                    mapboxMap.getUiSettings().setAttributionEnabled(false);
                    mapboxMap.addOnMapClickListener(MainActivity.this);
                    try {
                        setupAbly();
                    } catch(AblyException err) {
                        Log.e("ERROR", err.errorInfo.toString());
                    }
                });

    }

    public void setupAbly() throws AblyException {
        /* Instantiate a connection to Ably */
        AblyRealtime realtime = new AblyRealtime(R.string.ably_api_key);

        /* Create a reference to the Ably channel we'll be subscribing to */
        Channel channel = realtime.channels.get("[product:cttransit/gtfsr?rewind=500]vehicle:all");

        /* Subscribe to the channel, listening for any messages which come through */
        channel.subscribe((Channel.MessageListener) message -> {
            final JsonObject json = (JsonObject)message.data;

            JsonObject vehicle = json.getAsJsonObject("vehicle");
            JsonObject position = vehicle.getAsJsonObject("position");
            final Double longitude = position.get("longitude").getAsDouble();
            final Double latitude = position.get("latitude").getAsDouble();
            final String id = json.get("id").getAsString();

            runOnUiThread(() -> {
                JsonObject featureDetails = getDetails(vehicle);
                Feature tmpFeature = Feature.fromGeometry(
                        Point.fromLngLat(longitude, latitude),
                        featureDetails, id);
                if (buses.get(id) == null) {
                    tmpFeature.addBooleanProperty(BUS_SELECTED, false);

                    buses.put(id, tmpFeature);
                    Point newPoint = Point.fromLngLat(longitude, latitude);
                    busPoints.put(id, new ArrayList<>());
                    busPoints.get(id).add(newPoint);
                } else {
                    Point newPoint = Point.fromLngLat(longitude, latitude);
                    busPoints.get(id).add(newPoint);
                    if (busIsMoving.get(id) == null || busIsMoving.get(id) == false) {
                        animate(id);
                    }
                }
                refreshSource();
            });
        });
        setupData();
    }

    public JsonObject getDetails(JsonObject vehicle)  {
        JsonObject featureDetails = new JsonObject();
        featureDetails.addProperty("startDate", vehicle.getAsJsonObject("trip").get("startDate").getAsString());
        featureDetails.addProperty("routeId", vehicle.getAsJsonObject("trip").get("routeId").getAsString());
        featureDetails.addProperty("tripId", vehicle.getAsJsonObject("trip").get("tripId").getAsString());

        return featureDetails;
    }

    public void setupData() {
        if (mapboxMap == null) {
            return;
        }
        mapboxMap.getStyle(style -> {
            setupSource(style);
            setupLayer(style);
            setupCalloutLayer(style);
            setupRecyclerView();
        });
    }

    private void setupSource(@NonNull Style loadedMapStyle) {
        source = new GeoJsonSource("SOURCE_ID", featureCollection);
        loadedMapStyle.addSource(source);
        loadedMapStyle.addSource(lineSource = new GeoJsonSource(LINE_ID));
        loadedMapStyle.addLayerBelow(new LineLayer(LINE_LAYER_ID, LINE_ID).withProperties(
                lineColor(Color.parseColor("#F13C6E")),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(4f)), "road-label");
    }

    private void refreshSource() {
        featureCollection.features().clear();
        featureCollection.features().addAll(buses.values());
        if (source != null && featureCollection != null) {
            source.setGeoJson(featureCollection);
        }
    }

    /**
     * Setup a layer
     */
    private void setupLayer(@NonNull Style loadedMapStyle) {
        loadedMapStyle.addLayer(new SymbolLayer(BUS_LAYER_ID, "SOURCE_ID")
                .withProperties(PropertyFactory.iconImage(ICON_ID),
                        iconAllowOverlap(true),
                        iconSize(1f),
                        iconOffset(new Float[]{0f, -9f}))
        );
    }

    /**
     * Setup a layer with Android SDK call-outs
     * <p>
     * title of the feature is used as key for the iconImage
     * </p>
     */
    private void setupCalloutLayer(@NonNull Style loadedMapStyle) {
        loadedMapStyle.addLayer(new SymbolLayer(CALLOUT_LAYER_ID, "SOURCE_ID")
                .withProperties(
                        /* show image with id title based on the value of the title feature property */
                        iconImage("{title}"),

                        /* set anchor of icon to bottom-left */
                        iconAnchor(Property.ICON_ANCHOR_BOTTOM_LEFT),

                        /* offset icon slightly to match bubble layout */
                        iconOffset(new Float[] {-20.0f, -10.0f})
                )

                /* add a filter to show only when selected feature property is true */
                .withFilter(eq((get(BUS_SELECTED)), literal(true))));
    }

    private void setupRecyclerView() {
        RecyclerView.Adapter adapter = new LocationRecyclerViewAdapter(this, featureCollection.features());
        final LinearLayoutManager layoutManager = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setItemAnimator(new DefaultItemAnimator());
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == SCROLL_STATE_IDLE) {
                    int index = layoutManager.findFirstVisibleItemPosition();
                    setSelected(index, false);
                }
            }
        });
        SnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(recyclerView);
    }


    @Override
    public boolean onMapClick(@NonNull LatLng point) {
        PointF screenPoint = mapboxMap.getProjection().toScreenLocation(point);
        return handleClickBus(screenPoint);
    }

    private boolean handleClickBus(PointF screenPoint) {
        List<Feature> features = mapboxMap.queryRenderedFeatures(screenPoint, BUS_LAYER_ID);
        if (!features.isEmpty()) {
            for (int i = 0; i < featureCollection.features().size(); i++) {
                if (featureCollection.features().get(i).id().equals(features.get(0).id())) {
                    setSelected(i, true);
                }
            }

            return true;
        } else {
            if (recyclerView.getVisibility() != View.GONE) {
                recyclerView.setVisibility(View.GONE);
            }

            deselectAll(false);
        }
        return false;
    }

    /**
     * Set a feature selected state with the ability to scroll the RecycleViewer to the provided index.
     *
     * @param index      the index of selected feature
     * @param withScroll indicates if the recyclerView position should be updated
     */
    private void setSelected(int index, boolean withScroll) {
        if (recyclerView.getVisibility() == View.GONE) {
            recyclerView.setVisibility(View.VISIBLE);
        }

        deselectAll(false);

        Feature feature = this.featureCollection.features().get(index);
        selectFeature(feature);
        animateCameraToSelection(feature);
        refreshSource();

        if (withScroll) {
            recyclerView.scrollToPosition(index);
        }
    }

    /**
     * Deselects the state of all the features
     */
    private void deselectAll(boolean hideRecycler) {
        markerLinePointList.clear();
        for (Feature feature : buses.values()) {
            feature.properties().addProperty(BUS_SELECTED, false);
        }

        if (hideRecycler) {
            recyclerView.setVisibility(View.GONE);
        }
    }

    /**
     * Selects the state of a feature
     *
     * @param feature the feature to be selected.
     */
    private void selectFeature(Feature feature) {
        feature.properties().addProperty(BUS_SELECTED, true);
    }

    private Feature getSelectedFeature() {
        if (featureCollection != null) {
            for (Feature feature : buses.values()) {
                if (feature.getBooleanProperty(BUS_SELECTED)) {
                    return feature;
                }
            }
        }

        return null;
    }

    /**
     * Animate camera to a feature.
     *
     * @param feature the feature to animate to
     */
    private void animateCameraToSelection(Feature feature, double newZoom) {
        CameraPosition cameraPosition = mapboxMap.getCameraPosition();

        if (animatorSet != null) {
            animatorSet.cancel();
        }

        animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                createLatLngAnimator(cameraPosition.target, convertToLatLng(feature)),
                createZoomAnimator(cameraPosition.zoom, newZoom)
        );
        animatorSet.start();
    }

    private void animateCameraToSelection(Feature feature) {
        double zoom = 16.0f;
        animateCameraToSelection(feature, zoom);
    }

    private void setActivityStep(@ActivityStep int activityStep) {
        Feature selectedFeature = getSelectedFeature();
        double zoom = stepZoomMap.get(activityStep);
        animateCameraToSelection(selectedFeature, zoom);

        currentStep = activityStep;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mapView.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapboxMap != null) {
            mapboxMap.removeOnMapClickListener(this);
        }
        mapView.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentStep == STEP_LOADING || currentStep == STEP_READY) {
            setActivityStep(STEP_INITIAL);
            deselectAll(true);
            refreshSource();
        } else {
            super.onBackPressed();
        }
    }

    private void animate(String id) {
        if(currentBusStep.get(id) == null) {
            currentBusStep.put(id, 0);
        }
        Integer routeIndex = currentBusStep.get(id);

        if ((busPoints.get(id).size() - 2 > routeIndex)) {
            Point startPos = busPoints.get(id).get(routeIndex);
            Point endPos = busPoints.get(id).get(routeIndex + 1);
            busIsMoving.put(id, true);
            currentAnimator.put(id, createLatLngAnimatorForTracking(id, startPos, endPos));
            currentAnimator.get(id).start();

            currentBusStep.put(id, routeIndex + 1);
        } else {
            busIsMoving.put(id, false);
        }
    }

    private static class PointEvaluator implements TypeEvaluator<Point> {

        @Override
        public Point evaluate(float fraction, Point startValue, Point endValue) {
            return Point.fromLngLat(
                    startValue.longitude() + ((endValue.longitude() - startValue.longitude()) * fraction),
                    startValue.latitude() + ((endValue.latitude() - startValue.latitude()) * fraction)
            );
        }
    }

    private Animator createLatLngAnimatorForTracking(final String id, Point currentPosition, Point targetPosition) {
        ValueAnimator latLngAnimator = ValueAnimator.ofObject(new PointEvaluator(), currentPosition, targetPosition);
        latLngAnimator.setDuration(40000L);
        latLngAnimator.setInterpolator(new LinearInterpolator());
        latLngAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                animate(id);
            }
        });
        latLngAnimator.addUpdateListener(animation -> {
            Point point = (Point) animation.getAnimatedValue();
            buses.put(id, Feature.fromGeometry(
                    point,
                    buses.get(id).properties(), id));

            Boolean isSelected = buses.get(id).getBooleanProperty(BUS_SELECTED);

            if(isSelected != null && isSelected == true) {
                markerLinePointList.add(point);
                lineSource.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(markerLinePointList)));
            }
            refreshSource();
        });

        return latLngAnimator;
    }

    private LatLng convertToLatLng(Feature feature) {
        Point symbolPoint = (Point) feature.geometry();
        return new LatLng(symbolPoint.latitude(), symbolPoint.longitude());
    }

    private Animator createLatLngAnimator(LatLng currentPosition, LatLng targetPosition) {
        ValueAnimator latLngAnimator = ValueAnimator.ofObject(new LatLngEvaluator(), currentPosition, targetPosition);
        latLngAnimator.setDuration(CAMERA_ANIMATION_TIME);
        latLngAnimator.setInterpolator(new FastOutSlowInInterpolator());
        latLngAnimator.addUpdateListener(animation -> mapboxMap.moveCamera(CameraUpdateFactory.newLatLng((LatLng) animation.getAnimatedValue())));
        return latLngAnimator;
    }

    private Animator createZoomAnimator(double currentZoom, double targetZoom) {
        ValueAnimator zoomAnimator = ValueAnimator.ofFloat((float) currentZoom, (float) targetZoom);
        zoomAnimator.setDuration(CAMERA_ANIMATION_TIME);
        zoomAnimator.setInterpolator(new FastOutSlowInInterpolator());
        zoomAnimator.addUpdateListener(animation -> mapboxMap.moveCamera(CameraUpdateFactory.zoomTo((Float) animation.getAnimatedValue())));
        return zoomAnimator;
    }

    /**
     * Helper class to evaluate LatLng objects with a ValueAnimator
     */
    private static class LatLngEvaluator implements TypeEvaluator<LatLng> {

        private final LatLng latLng = new LatLng();

        @Override
        public LatLng evaluate(float fraction, LatLng startValue, LatLng endValue) {
            latLng.setLatitude(startValue.getLatitude()
                    + ((endValue.getLatitude() - startValue.getLatitude()) * fraction));
            latLng.setLongitude(startValue.getLongitude()
                    + ((endValue.getLongitude() - startValue.getLongitude()) * fraction));
            return latLng;
        }
    }

    /**
     * RecyclerViewAdapter adapting features to cards.
     */
    static class LocationRecyclerViewAdapter extends
            RecyclerView.Adapter<MainActivity.LocationRecyclerViewAdapter.MyViewHolder> {

        private List<Feature> featureCollection;
        private MainActivity activity;

        LocationRecyclerViewAdapter(MainActivity activity, List<Feature> featureList) {
            this.activity = activity;
            this.featureCollection = featureList;
        }

        @Override
        public LocationRecyclerViewAdapter.MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.cardview_symbol_layer, parent, false);
            return new LocationRecyclerViewAdapter.MyViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(LocationRecyclerViewAdapter.MyViewHolder holder, int position) {
            Feature feature = featureCollection.get(position);
            holder.title.setText("Bus: " + feature.id());
            holder.description.setText("This is the " + feature.id() + " on trip " + feature.getStringProperty("tripId"));
            holder.poi.setText("Route ID: " + feature.getStringProperty("routeId"));
            holder.style.setText("Date" + feature.getStringProperty("startDate"));
        }

        @Override
        public int getItemCount() {
            return featureCollection.size();
        }

        /**
         * ViewHolder for RecyclerView.
         */
        static class MyViewHolder extends RecyclerView.ViewHolder  {
            TextView title;
            TextView poi;
            TextView style;
            TextView description;
            CardView singleCard;

            MyViewHolder(View view) {
                super(view);
                title = view.findViewById(R.id.textview_title);
                poi = view.findViewById(R.id.textview_poi);
                style = view.findViewById(R.id.textview_style);
                description = view.findViewById(R.id.textview_description);
                singleCard = view.findViewById(R.id.single_location_cardview);
            }
        }
    }
}