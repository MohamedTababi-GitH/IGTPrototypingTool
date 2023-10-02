package util;

public class Vector3D {

    /**
     * The 3 components of the vector in an array
     */
    protected double[] vector = new double[3];

    /**
     * Create a vector where all components are set to zero
     */
    public Vector3D() {
        for (int i = 0; i < 3; i++) {
            vector[i] = 0;
        }
    }

    /**
     * Create a vector where the components are set to the parameters
     * @param x the desired x component of the vector
     * @param y the desired y component of the vector
     * @param z the desired z component of the vector
     */
    public Vector3D(double x, double y, double z) {
        vector[0] = x;
        vector[1] = y;
        vector[2] = z;
    }

    /**
     * Create a vector where the components are set to the parameters
     */
    public Vector3D(double[] arr) {
        if (arr.length == 3) {
            vector[0] = arr[0];
            vector[1] = arr[1];
            vector[2] = arr[2];
        }
    }

    /**
     * Method to set the vectors components
     *
     * @param i the position of the component
     * @param num the desired value of the component
     */
    public void set(int i, double num) {
        if (i >= 0 && i < 3) {
            vector[i] = num;
        }
    }

    /**
     * Method to get the value at a given position
     *
     * @param i the position
     * @return the value at position i
     */
    public double get(int i) {
        return vector[i];
    }

    /**
     * Gets the magnitude of the vector
     *
     * @return the magnitude of the vector
     */
    public double getMag() {
        double sum = 0;
        for (int i = 0; i < vector.length; i++) {
            sum += vector[i] * vector[i];
        }
        return Math.sqrt(sum);
    }

    /**
     * Sets the magnitude of the vector
     *
     * @param new_mag the desired magnitude
     */
    public void setMag(double new_mag) {
        double mag = getMag();
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] * new_mag / mag;
        }
    }

    /**
     * Adds a vector to the current vector
     *
     * @param other the other vector to be added to this one
     */
    public void add(Vector3D other) {
        for (int i = 0; i < this.vector.length; i++) {
            this.vector[i] += other.vector[i];
        }
    }

    /**
     * Scalar multiply a vector with a number
     *
     * @param scalar the scalar
     */
    public void mult(double scalar) {
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scalar;
        }
    }
    /**
     * Helper function to show the vector on the console
     */
    public void print() {
        System.out.println("(" + vector[0] + ", " + vector[1] + ", " + vector[2] + ")");
    }
}
