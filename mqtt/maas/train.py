"""
Offline trening modela za MaaS servis.

Regresija: predikcija temperature na osnovu ostalih senzorskih velicina iz iste poruke
(humidity, pressure, light, sound, motion, location) — "virtuelni senzor temperature".
Analytics servis salje prosecne vrednosti iz tumbling window-a i poredi predvidjenu
temperaturu sa stvarnom.

Trening/validacija/test = 70/15/15, hronoloski (bez mesanja).

Pokrece se jednom, lokalno:  python train.py
Rezultat: model.joblib
"""
import joblib
import pandas as pd
from sklearn.ensemble import RandomForestRegressor
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_absolute_error, r2_score

CSV_PATH = "../src/main/resources/real_time_data.csv"
MAX_ROWS = 150_000
LOCATIONS = ["Outside", "Room A", "Room B", "Room C"]
FEATURES = ["humidity", "pressure", "light", "sound", "motion"] + [f"loc_{l}" for l in LOCATIONS]


def build_dataset():
    df = pd.read_csv(CSV_PATH, nrows=MAX_ROWS)
    for loc in LOCATIONS:
        df[f"loc_{loc}"] = (df["location"] == loc).astype(int)
    # numpy nizovi (bez imena kolona) — servis salje sirove nizove u istom redosledu
    return df[FEATURES].to_numpy(), df["temperature"].to_numpy()


def evaluate(name, model, X_val, y_val, X_test, y_test):
    val_pred = model.predict(X_val)
    test_pred = model.predict(X_test)
    print(f"{name}:")
    print(f"  validacija -> MAE: {mean_absolute_error(y_val, val_pred):.3f}  R2: {r2_score(y_val, val_pred):.3f}")
    print(f"  test       -> MAE: {mean_absolute_error(y_test, test_pred):.3f}  R2: {r2_score(y_test, test_pred):.3f}")


def main():
    X, y = build_dataset()
    print(f"Dataset: {len(X)} uzoraka, featuri: {FEATURES}")

    n = len(X)
    i1, i2 = int(n * 0.70), int(n * 0.85)
    X_train, y_train = X[:i1], y[:i1]
    X_val, y_val = X[i1:i2], y[i1:i2]
    X_test, y_test = X[i2:], y[i2:]

    baseline = LinearRegression().fit(X_train, y_train)
    evaluate("LinearRegression (baseline)", baseline, X_val, y_val, X_test, y_test)

    forest = RandomForestRegressor(n_estimators=30, max_depth=10, n_jobs=-1, random_state=42)
    forest.fit(X_train, y_train)
    evaluate("RandomForestRegressor", forest, X_val, y_val, X_test, y_test)

    joblib.dump(forest, "model.joblib", compress=3)
    print("Model sacuvan u model.joblib")


if __name__ == "__main__":
    main()
