package org.deeplearning4j.nn;

import static org.deeplearning4j.util.MatrixUtil.log;
import static org.deeplearning4j.util.MatrixUtil.oneMinus;
import static org.deeplearning4j.util.MatrixUtil.sigmoid;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;

import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.random.MersenneTwister;
import org.apache.commons.math3.random.RandomGenerator;
import org.deeplearning4j.dbn.DBN;
import org.deeplearning4j.nn.learning.AdaGrad;
import org.deeplearning4j.optimize.NeuralNetworkOptimizer;
import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;


/**
 * Baseline class for any Neural Network used
 * as a layer in a deep network such as an {@link DBN}
 * @author Adam Gibson
 *
 */
public abstract class BaseNeuralNetwork implements NeuralNetwork,Persistable {




	private static final long serialVersionUID = -7074102204433996574L;
	/* Number of visible inputs */
	public int nVisible;
	/**
	 * Number of hidden units
	 * One tip with this is usually having
	 * more hidden units than inputs (read: input rows here)
	 * will typically cause terrible overfitting.
	 * 
	 * Another rule worthy of note: more training data typically results
	 * in more redundant data. It is usually a better idea to use a smaller number
	 * of hidden units.
	 *  
	 *  
	 *   
	 **/
	public int nHidden;
	/* Weight matrix */
	public DoubleMatrix W;
	/* hidden bias */
	public DoubleMatrix hBias;
	/* visible bias */
	public DoubleMatrix vBias;
	/* RNG for sampling. */
	public RandomGenerator rng;
	/* input to the network */
	public DoubleMatrix input;
	/* sparsity target */
	public double sparsity = 0.01;
	/* momentum for learning */
	public double momentum = 0.1;
	/* Probability distribution for weights */
	public transient RealDistribution dist = new NormalDistribution(rng,0,.01,NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
	/* L2 Regularization constant */
	public double l2 = 0.1;
	public transient NeuralNetworkOptimizer optimizer;
	public int renderWeightsEveryNumEpochs = -1;
	public double fanIn = -1;
	public boolean useRegularization = true;
	public boolean useAdaGrad = false;

	public AdaGrad wAdaGrad;

	public BaseNeuralNetwork() {}
	/**
	 * 
	 * @param nVisible the number of outbound nodes
	 * @param nHidden the number of nodes in the hidden layer
	 * @param W the weights for this vector, maybe null, if so this will
	 * create a matrix with nHidden x nVisible dimensions.
	 * @param hBias the hidden bias
	 * @param vBias the visible bias (usually b for the output layer)
	 * @param rng the rng, if not a seed of 1234 is used.
	 */
	public BaseNeuralNetwork(int nVisible, int nHidden, 
			DoubleMatrix W, DoubleMatrix hbias, DoubleMatrix vbias, RandomGenerator rng,double fanIn,RealDistribution dist) {
		this(null,nVisible,nHidden,W,hbias,vbias,rng,fanIn,dist);

	}

	/**
	 * 
	 * @param input the input examples
	 * @param nVisible the number of outbound nodes
	 * @param nHidden the number of nodes in the hidden layer
	 * @param W the weights for this vector, maybe null, if so this will
	 * create a matrix with nHidden x nVisible dimensions.
	 * @param hBias the hidden bias
	 * @param vBias the visible bias (usually b for the output layer)
	 * @param rng the rng, if not a seed of 1234 is used.
	 */
	public BaseNeuralNetwork(DoubleMatrix input, int nVisible, int nHidden, 
			DoubleMatrix W, DoubleMatrix hbias, DoubleMatrix vbias, RandomGenerator rng,double fanIn,RealDistribution dist) {
		this.nVisible = nVisible;
		if(dist != null)
			this.dist = dist;
		else
			this.dist = new NormalDistribution(rng,0,.01,NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
		this.nHidden = nHidden;
		this.fanIn = fanIn;
		this.input = input;
		if(rng == null)	
			this.rng = new MersenneTwister(1234);

		else 
			this.rng = rng;
		this.W = W;
		if(this.W != null)
			this.wAdaGrad = new AdaGrad(this.W.rows,this.W.columns);

		this.vBias = vbias;
		this.hBias = hbias;

		initWeights();	


	}



	@Override
	public double l2RegularizedCoefficient() {
		return (MatrixFunctions.pow(getW(),2).sum()/ 2.0)  * l2;
	}

	/**
	 * Initialize weights.
	 * This includes steps for doing a random initialization of W
	 * as well as the vbias and hbias
	 */
	protected void initWeights()  {

		if(this.nVisible < 1)
			throw new IllegalStateException("Number of visible can not be less than 1");
		if(this.nHidden < 1)
			throw new IllegalStateException("Number of hidden can not be less than 1");

		if(this.dist == null)
			dist = new NormalDistribution(rng,0,.01,NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
		/*
		 * Initialize based on the number of visible units..
		 * The lower bound is called the fan in
		 * The outer bound is called the fan out.
		 * 
		 * Below's advice works for Denoising AutoEncoders and other 
		 * neural networks you will use due to the same baseline guiding principles for
		 * both RBMs and Denoising Autoencoders.
		 * 
		 * Hinton's Guide to practical RBMs:
		 * The weights are typically initialized to small random values chosen from a zero-mean Gaussian with
		 * a standard deviation of about 0.01. Using larger random values can speed the initial learning, but
		 * it may lead to a slightly worse final model. Care should be taken to ensure that the initial weight
		 * values do not allow typical visible vectors to drive the hidden unit probabilities very close to 1 or 0
		 * as this significantly slows the learning.
		 */
		if(this.W == null) {

			this.W = DoubleMatrix.zeros(nVisible,nHidden);

			for(int i = 0; i < this.W.rows; i++) 
				this.W.putRow(i,new DoubleMatrix(dist.sample(this.W.columns)));

		}

		this.wAdaGrad = new AdaGrad(this.W.rows,this.W.columns);

		if(this.hBias == null) {
			this.hBias = DoubleMatrix.zeros(nHidden);
			/*
			 * Encourage sparsity.
			 * See Hinton's Practical guide to RBMs
			 */
			//this.hBias.subi(4);
		}

		if(this.vBias == null) {
			if(this.input != null) {

				this.vBias = DoubleMatrix.zeros(nVisible);


			}
			else
				this.vBias = DoubleMatrix.zeros(nVisible);
		}



	}


	@Override
	public void setRenderEpochs(int renderEpochs) {
		this.renderWeightsEveryNumEpochs = renderEpochs;

	}
	@Override
	public int getRenderEpochs() {
		return renderWeightsEveryNumEpochs;
	}

	@Override
	public double fanIn() {
		return fanIn < 0 ? 1 / nVisible : fanIn;
	}

	@Override
	public void setFanIn(double fanIn) {
		this.fanIn = fanIn;
	}


	public void jostleWeighMatrix() {
		/*
		 * Initialize based on the number of visible units..
		 * The lower bound is called the fan in
		 * The outer bound is called the fan out.
		 * 
		 * Below's advice works for Denoising AutoEncoders and other 
		 * neural networks you will use due to the same baseline guiding principles for
		 * both RBMs and Denoising Autoencoders.
		 * 
		 * Hinton's Guide to practical RBMs:
		 * The weights are typically initialized to small random values chosen from a zero-mean Gaussian with
		 * a standard deviation of about 0.01. Using larger random values can speed the initial learning, but
		 * it may lead to a slightly worse final model. Care should be taken to ensure that the initial weight
		 * values do not allow typical visible vectors to drive the hidden unit probabilities very close to 1 or 0
		 * as this significantly slows the learning.
		 */

		DoubleMatrix W = DoubleMatrix.zeros(nVisible,nHidden);
		for(int i = 0; i < this.W.rows; i++) 
			W.putRow(i,new DoubleMatrix(dist.sample(this.W.columns)));




	}





	@Override
	public AdaGrad getAdaGrad() {
		return this.wAdaGrad;
	}
	@Override
	public void setAdaGrad(AdaGrad adaGrad) {
		this.wAdaGrad = adaGrad;
	}

	@Override
	public NeuralNetwork transpose() {
		try {
			NeuralNetwork ret = getClass().newInstance();
			ret.sethBias(hBias.dup());
			ret.setvBias(vBias.dup());
			ret.setnHidden(getnVisible());
			ret.setnVisible(getnHidden());
			ret.setW(W.transpose());
			ret.setRng(getRng());
			ret.setAdaGrad(wAdaGrad);
			ret.setDist(getDist());

			return ret;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} 


	}

	@Override
	public NeuralNetwork clone() {
		try {
			NeuralNetwork ret = getClass().newInstance();
			ret.sethBias(hBias.dup());
			ret.setvBias(vBias.dup());
			ret.setnHidden(getnHidden());
			ret.setnVisible(getnVisible());
			ret.setW(W.dup());
			ret.setRng(getRng());
			ret.setDist(getDist());
			ret.setAdaGrad(wAdaGrad);
			return ret;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} 


	}

	@Override
	public RealDistribution getDist() {
		return dist;
	}

	@Override
	public void setDist(RealDistribution dist) {
		this.dist = dist;
	}

	@Override
	public void merge(NeuralNetwork network,int batchSize) {
		if(this.useRegularization) {
			W.addi(network.getW().mini(W).div(batchSize));
			hBias.addi(network.gethBias().subi(hBias).divi(batchSize));
			vBias.addi(network.getvBias().subi(vBias).divi(batchSize));
		}

		else {
			W.addi(network.getW().mini(W));
			hBias.addi(network.gethBias().subi(hBias));
			vBias.addi(network.getvBias().subi(vBias));
		}

	}



	/**
	 * Copies params from the passed in network
	 * to this one
	 * @param n the network to copy
	 */
	public void update(BaseNeuralNetwork n) {
		this.W = n.W;
		this.hBias = n.hBias;
		this.vBias = n.vBias;
		this.l2 = n.l2;
		this.useRegularization = n.useRegularization;
		this.momentum = n.momentum;
		this.nHidden = n.nHidden;
		this.nVisible = n.nVisible;
		this.rng = n.rng;
		this.sparsity = n.sparsity;
		this.wAdaGrad = n.wAdaGrad;

	}

	/**
	 * Load (using {@link ObjectInputStream}
	 * @param is the input stream to load from (usually a file)
	 */
	public void load(InputStream is) {
		try {
			ObjectInputStream ois = new ObjectInputStream(is);
			BaseNeuralNetwork loaded = (BaseNeuralNetwork) ois.readObject();
			update(loaded);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

	}


	/**
	 * Reconstruction entropy.
	 * This compares the similarity of two probability
	 * distributions, in this case that would be the input
	 * and the reconstructed input with gaussian noise.
	 * This will account for either regularization or none
	 * depending on the configuration.
	 * @return reconstruction error
	 */
	public double getReConstructionCrossEntropy() {
		DoubleMatrix preSigH = input.mmul(W).addRowVector(hBias);
		DoubleMatrix sigH = sigmoid(preSigH);

		DoubleMatrix preSigV = sigH.mmul(W.transpose()).addRowVector(vBias);
		DoubleMatrix sigV = sigmoid(preSigV);
		DoubleMatrix inner = 
				input.mul(log(sigV))
				.add(oneMinus(input)
						.mul(log(oneMinus(sigV))));
		double l = inner.length;
		if(useRegularization) {
			double normalized = l + l2RegularizedCoefficient();
			return - inner.rowSums().mean() / normalized;
		}

		double ret =  - inner.rowSums().mean();


		return ret;
	}


	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#getnVisible()
	 */
	@Override
	public int getnVisible() {
		return nVisible;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#setnVisible(int)
	 */
	@Override
	public void setnVisible(int nVisible) {
		this.nVisible = nVisible;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#getnHidden()
	 */
	@Override
	public int getnHidden() {
		return nHidden;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#setnHidden(int)
	 */
	@Override
	public void setnHidden(int nHidden) {
		this.nHidden = nHidden;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#getW()
	 */
	@Override
	public DoubleMatrix getW() {
		return W;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#setW(org.jblas.DoubleMatrix)
	 */
	@Override
	public void setW(DoubleMatrix w) {
		W = w;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#gethBias()
	 */
	@Override
	public DoubleMatrix gethBias() {
		return hBias;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#sethBias(org.jblas.DoubleMatrix)
	 */
	@Override
	public void sethBias(DoubleMatrix hBias) {
		this.hBias = hBias;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#getvBias()
	 */
	@Override
	public DoubleMatrix getvBias() {
		return vBias;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#setvBias(org.jblas.DoubleMatrix)
	 */
	@Override
	public void setvBias(DoubleMatrix vBias) {
		this.vBias = vBias;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#getRng()
	 */
	@Override
	public RandomGenerator getRng() {
		return rng;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#setRng(org.apache.commons.math3.random.RandomGenerator)
	 */
	@Override
	public void setRng(RandomGenerator rng) {
		this.rng = rng;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#getInput()
	 */
	@Override
	public DoubleMatrix getInput() {
		return input;
	}

	/* (non-Javadoc)
	 * @see org.deeplearning4j.nn.NeuralNetwork#setInput(org.jblas.DoubleMatrix)
	 */
	@Override
	public void setInput(DoubleMatrix input) {
		this.input = input;
	}


	public double getSparsity() {
		return sparsity;
	}
	public void setSparsity(double sparsity) {
		this.sparsity = sparsity;
	}
	public double getMomentum() {
		return momentum;
	}
	public void setMomentum(double momentum) {
		this.momentum = momentum;
	}
	public double getL2() {
		return l2;
	}
	public void setL2(double l2) {
		this.l2 = l2;
	}

	/**
	 * Write this to an object output stream
	 * @param os the output stream to write to
	 */
	public void write(OutputStream os) {
		try {
			ObjectOutputStream os2 = new ObjectOutputStream(os);
			os2.writeObject(this);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * All neural networks are based on this idea of 
	 * minimizing reconstruction error.
	 * Both RBMs and Denoising AutoEncoders
	 * have a component for reconstructing, ala different implementations.
	 *  
	 * @param x the input to reconstruct
	 * @return the reconstructed input
	 */
	public abstract DoubleMatrix reconstruct(DoubleMatrix x);

	/**
	 * The loss function (cross entropy, reconstruction error,...)
	 * @return the loss function
	 */
	public abstract double lossFunction(Object[] params);


	public double lossFunction() {
		return lossFunction(null);
	}

	/**
	 * Train one iteration of the network
	 * @param input the input to train on
	 * @param lr the learning rate to train at
	 * @param params the extra params (k, corruption level,...)
	 */
	@Override
	public abstract void train(DoubleMatrix input,double lr,Object[] params);

	@Override
	public double squaredLoss() {
		DoubleMatrix reconstructed = reconstruct(input);
		double loss = MatrixFunctions.powi(reconstructed.sub(input), 2).sum() / input.rows;
		if(this.useRegularization) {
			loss += 0.5 * l2 * MatrixFunctions.pow(W,2).sum();
		}

		return -loss;
	}



	public static class Builder<E extends BaseNeuralNetwork> {
		private E ret = null;
		private DoubleMatrix W;
		protected Class<? extends NeuralNetwork> clazz;
		private DoubleMatrix vBias;
		private DoubleMatrix hBias;
		private int numVisible;
		private int numHidden;
		private RandomGenerator gen = new MersenneTwister(123);
		private DoubleMatrix input;
		private double sparsity = 0.01;
		private double l2 = 0.01;
		private double momentum = 0.1;
		private int renderWeightsEveryNumEpochs = -1;
		private double fanIn = 0.1;
		private boolean useRegularization = true;
		private RealDistribution dist;
		private boolean useAdaGrad = false;

		public Builder<E> useAdaGrad(boolean useAdaGrad) {
			this.useAdaGrad = useAdaGrad;
			return this;
		}

		public Builder<E> withDistribution(RealDistribution dist) {
			this.dist = dist;
			return this;
		}

		public Builder<E> useRegularization(boolean useRegularization) {
			this.useRegularization = useRegularization;
			return this;
		}

		public Builder<E> fanIn(double fanIn) {
			this.fanIn = fanIn;
			return this;
		}

		public Builder<E> withL2(double l2) {
			this.l2 = l2;
			return this;
		}


		public Builder<E> renderWeights(int numEpochs) {
			this.renderWeightsEveryNumEpochs = numEpochs;
			return this;
		}

		@SuppressWarnings("unchecked")
		public E buildEmpty() {
			try {
				return (E) clazz.newInstance();
			} catch (InstantiationException | IllegalAccessException e) {
				throw new RuntimeException(e);
			}
		}



		public Builder<E> withClazz(Class<? extends BaseNeuralNetwork> clazz) {
			this.clazz = clazz;
			return this;
		}

		public Builder<E> withSparsity(double sparsity) {
			this.sparsity = sparsity;
			return this;
		}
		public Builder<E> withMomentum(double momentum) {
			this.momentum = momentum;
			return this;
		}

		public Builder<E> withInput(DoubleMatrix input) {
			this.input = input;
			return this;
		}

		public Builder<E> asType(Class<E> clazz) {
			this.clazz = clazz;
			return this;
		}


		public Builder<E> withWeights(DoubleMatrix W) {
			this.W = W;
			return this;
		}

		public Builder<E> withVisibleBias(DoubleMatrix vBias) {
			this.vBias = vBias;
			return this;
		}

		public Builder<E> withHBias(DoubleMatrix hBias) {
			this.hBias = hBias;
			return this;
		}

		public Builder<E> numberOfVisible(int numVisible) {
			this.numVisible = numVisible;
			return this;
		}

		public Builder<E> numHidden(int numHidden) {
			this.numHidden = numHidden;
			return this;
		}

		public Builder<E> withRandom(RandomGenerator gen) {
			this.gen = gen;
			return this;
		}

		public E build() {
			return buildWithInput();

		}


		@SuppressWarnings("unchecked")
		private  E buildWithInput()  {
			Constructor<?>[] c = clazz.getDeclaredConstructors();
			for(int i = 0; i < c.length; i++) {
				Constructor<?> curr = c[i];
				Class<?>[] classes = curr.getParameterTypes();
				//input matrix found
				if(classes != null && classes.length > 0 && classes[0].isAssignableFrom(DoubleMatrix.class)) {
					try {
						ret = (E) curr.newInstance(input,numVisible, numHidden, W, hBias,vBias, gen,fanIn,dist);
						ret.sparsity = this.sparsity;
						ret.renderWeightsEveryNumEpochs = this.renderWeightsEveryNumEpochs;
						ret.l2 = this.l2;
						ret.momentum = this.momentum;
						ret.useRegularization = this.useRegularization;
						ret.useAdaGrad = this.useAdaGrad;
						return ret;
					}catch(Exception e) {
						throw new RuntimeException(e);
					}

				}
			}
			return ret;
		}
	}



}
