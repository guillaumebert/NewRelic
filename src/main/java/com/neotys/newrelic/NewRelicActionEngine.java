package com.neotys.newrelic;

import com.neotys.action.result.ResultFactory;
import com.neotys.extensions.action.ActionParameter;
import com.neotys.extensions.action.engine.ActionEngine;
import com.neotys.extensions.action.engine.Context;
import com.neotys.extensions.action.engine.SampleResult;
import com.neotys.newrelic.fromnlweb.NLWebClient;
import com.neotys.newrelic.fromnlweb.NLWebElementValue;
import com.neotys.newrelic.fromnlweb.NLWebMainStatistics;
import com.neotys.newrelic.rest.NewRelicApplicationHost;
import com.neotys.newrelic.rest.NewRelicMetricData;
import com.neotys.newrelic.rest.NewRelicRestClient;
import com.neotys.rest.dataexchange.client.DataExchangeAPIClient;
import com.neotys.rest.dataexchange.client.DataExchangeAPIClientFactory;
import com.neotys.rest.dataexchange.model.ContextBuilder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.neotys.action.argument.Arguments.getArgumentLogString;
import static com.neotys.action.argument.Arguments.parseArguments;

public final class NewRelicActionEngine implements ActionEngine {

	@Override
	public SampleResult execute(Context context, List<ActionParameter> parameters) {
		final SampleResult sampleResult = new SampleResult();
		final StringBuilder requestContentBuilder = new StringBuilder();
		requestContentBuilder.append("Executing advanced action New Relic ");
		final StringBuilder responseContentBuilder = new StringBuilder();
		
		// Check last execution time (and fail if called less than 45 seconds ago).
		final Object newRelicLastExecutionTime = context.getCurrentVirtualUser().get(Constants.NEW_RELIC_LAST_EXECUTION_TIME);		
		final Long newRelicCurrentExecution = System.currentTimeMillis();
		context.getCurrentVirtualUser().put(Constants.NEW_RELIC_LAST_EXECUTION_TIME, newRelicCurrentExecution);
		if(!(newRelicLastExecutionTime instanceof Long)){
			requestContentBuilder.append("(first execution).\n");
		} else if((Long)newRelicLastExecutionTime + 45*1000 > newRelicCurrentExecution){
			return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_INSUFFICIENT_DELAY, "Not enough delay between the two New Relic advanced action execution. Make sure to have at least 60 seconds pacing on the Actions container.");			
		} else {
			requestContentBuilder.append("(last execution was " + ((newRelicCurrentExecution - (Long)newRelicLastExecutionTime)/1000) + " seconds ago)\n");
		}
		
		// Parse arguments
		final NewRelicActionArguments newRelicActionArguments;
		try {
			final Map<String, com.google.common.base.Optional<String>> parsedArgs = parseArguments(parameters, NewRelicOption.values());
			requestContentBuilder.append("Parameters: " + getArgumentLogString(parsedArgs, NewRelicOption.values()) + "\n");			
			newRelicActionArguments = new NewRelicActionArguments(parsedArgs);
		} catch (final IllegalArgumentException iae) {
			return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_INVALID_PARAMETER, "Issue while parsing advanced action arguments: ",
					iae);
		}

		// When option to sendNLWebDataToNewRelic is on, then 3 other parameters should be present as well
		if (newRelicActionArguments.isSendNLWebDataToNewRelic()) {
			if (!newRelicActionArguments.getNewRelicLicenseKey().isPresent()) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_INVALID_PARAMETER,
						"The New Relic license key is required when argument 'sendNLWebDataToNewRelic' is true.");
			}
			if (!newRelicActionArguments.getNewRelicAccountId().isPresent()) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_INVALID_PARAMETER,
						"The New Relic Account Id is required when argument 'sendNLWebDataToNewRelic' is true.");
			}
			if (!newRelicActionArguments.getNewRelicInsightsAPIKey().isPresent()) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_INVALID_PARAMETER,
						"The New Relic Insights API key Id is required when argument 'sendNLWebDataToNewRelic' is true.");
			}
		}

		// When option to sendNLWebDataToNewRelic is on, then a NeoLoad Web test must be running
		if (newRelicActionArguments.isSendNLWebDataToNewRelic()) {
			if (context.getWebPlatformRunningTestUrl() == null) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_BAD_CONTEXT,
						"A NeoLoad Web test should be running when argument 'sendNLWebDataToNewRelic' is true.");
			}
		}

		// Retrieve NewRelicRestClient from Context, or instantiate new one
		NewRelicRestClient newRelicRestClient = (NewRelicRestClient) context.getCurrentVirtualUser().get(Constants.NEW_RELIC_REST_CLIENT);
		if (newRelicRestClient == null) {
			try {
				newRelicRestClient = new NewRelicRestClient(newRelicActionArguments, context);
				context.getCurrentVirtualUser().put(Constants.NEW_RELIC_REST_CLIENT, newRelicRestClient);
				requestContentBuilder.append("NewRelicRestClient created.\n");
			} catch (final Exception e) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
						"Technical Error while creating New Relic Rest Client:", e);
			}
		} else {
			requestContentBuilder.append("NewRelicRestClient retrieved from User Path Context.\n");
		}

		// Retrieve DataExchangeAPIClient from Context, or instantiate new one
		DataExchangeAPIClient dataExchangeAPIClient = (DataExchangeAPIClient) context.getCurrentVirtualUser().get(Constants.NL_DATA_EXCHANGE_API_CLIENT);
		if (dataExchangeAPIClient == null) {
			try {
				final ContextBuilder contextBuilder = new ContextBuilder();
				contextBuilder.hardware(Constants.NEOLOAD_CONTEXT_HARDWARE).location(Constants.NEOLOAD_CONTEXT_LOCATION).software(
						Constants.NEOLOAD_CONTEXT_SOFTWARE).script("NewRelicInfrasfructureMonitoring" + System.currentTimeMillis());

				final String dataExchangeApiUrl = newRelicActionArguments.getDataExchangeApiUrl().orElseGet(() -> getDefaultDataExchangeApiUrl(context));

				if (context.getLogger().isDebugEnabled()) {
					context.getLogger().debug("Data Exchange API URL used: " + dataExchangeApiUrl);
				}
				dataExchangeAPIClient = DataExchangeAPIClientFactory.newClient(dataExchangeApiUrl,
						contextBuilder.build(),
						newRelicActionArguments.getDataExchangeApiKey().orElse(null));
				context.getCurrentVirtualUser().put(Constants.NL_DATA_EXCHANGE_API_CLIENT, dataExchangeAPIClient);
				requestContentBuilder.append("DataExchangeAPIClient created.\n");
			} catch (final Exception e) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
						"Technical Error while creating DataExchangeAPI Client:", e);
			}
		} else {
			requestContentBuilder.append("DataExchangeAPIClient retrieved from User Path Context.\n");
		}
		
		sampleResult.sampleStart();			
		
		/**
		 * 1. New Relic -> NeoLoad DataExchangeAPI
		 */
		try {				
			responseContentBuilder.append("Retrieving New Relic application hosts available. ");			
			final List<NewRelicApplicationHost> newRelicApplicationHosts = newRelicRestClient.getApplicationHosts();
			responseContentBuilder.append(newRelicApplicationHosts.size() + " host(s) found.\n");		
			for (final NewRelicApplicationHost newRelicApplicationHost : newRelicApplicationHosts) {
				responseContentBuilder.append("Retrieving Metrics Data for host " + newRelicApplicationHost.getHostName() + ".\n");			
				final List<String> metricNames = newRelicRestClient.getMetricNamesForHost(newRelicApplicationHost.getHostId());
				responseContentBuilder.append("\tMetric names found: " + metricNames + ".\n");								
				final List<NewRelicMetricData> newRelicMetricData = newRelicRestClient.getNewRelicMetricData(metricNames,
						newRelicApplicationHost.getHostId(), newRelicApplicationHost.getHostName());		
				responseContentBuilder.append("\tMetric data found: " + newRelicMetricData.size() + ".\n");			
				if (!newRelicMetricData.isEmpty()) {
					responseContentBuilder.append("\tSending metric data to NeoLoad DataExchange API.\n");
					dataExchangeAPIClient.addEntries(newRelicMetricData.stream().map(n -> n.buildEntry()).collect(Collectors.toList()));
				}
			}
		} catch (final Exception e) {			
			return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
					"Technical Error while sending New Relic Metric Data and inject them to NeoLoad through DataExchange API:", e);
		} 
			
		/**
		 * 2. NeoLoad Web -> New Relic
		 */			
		if (!newRelicActionArguments.isSendNLWebDataToNewRelic()) {
			responseContentBuilder.append("sendNLWebDataToNewRelic option disabled.\n");				
		} else {
			responseContentBuilder.append("sendNLWebDataToNewRelic enabled.\n");	
			
			// Retrieve NLWebClient from Context, or instantiate new one
			NLWebClient nlWebClient = (NLWebClient) context.getCurrentVirtualUser().get(Constants.NL_WEB_CLIENT);
			if (nlWebClient == null) {
				try {
					nlWebClient = new NLWebClient(context, newRelicActionArguments);
					context.getCurrentVirtualUser().put(Constants.NL_WEB_CLIENT, nlWebClient);
					requestContentBuilder.append("NLWebClient created.\n");
				} catch (final Exception e) {
					return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
							"Technical Error while creating NLWeb client:",	e);
				}
			} else {
				requestContentBuilder.append("NLWebClient retrieved from User Path Context.\n");
			}
			
			/**
			 * 2.1 Retrieve NeoLoad Web Main statistics
			 */
			final NLWebMainStatistics nlWebMainStatistics;
			try {				
				requestContentBuilder.append("Retrieve NeoLoad Web Main statistics.\n");
				nlWebMainStatistics = nlWebClient.getMainStatistics();						
			} catch (final Exception e) {			
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
						"Technical Error while retrieving NeoLoad Web Main statistics: ", e);
			} 
			
			/**
			 * 2.2 Retrieve NeoLoad Web Element Values
			 */
			final List<NLWebElementValue> nlWebElementValues;
			try {				
				requestContentBuilder.append("Retrieve NeoLoad Web Element Values.\n");
				nlWebElementValues = nlWebClient.getElementValues();			
			} catch (final Exception e) {			
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
						"Technical Error while retrieving NeoLoad Web Element Values: ", e);
			} 
			
			/**
			 * 2.3 Send NeoLoad Web Main statistics to Plateform API
			 */
			requestContentBuilder.append("Send NeoLoad Web Main statistics to Plateform API.\n");
			final Optional<String> errorMessagePlat = newRelicRestClient.sendNLWebMainStatisticsToPlateformAPI(nlWebMainStatistics);
			if (errorMessagePlat.isPresent()) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
						"Error while sending NeoLoad Web Main statistics to Plateform API: " + errorMessagePlat.get());
			}

			/**
			 * 2.4 Send NeoLoad Web Main statistics to Insights API
			 */
			requestContentBuilder.append("Send NeoLoad Web Main statistics to Insights API.\n");
			final Optional<String> errorMessageInsight = newRelicRestClient.sendNLWebMainStatisticsToInsightsAPI(nlWebMainStatistics);
			if (errorMessageInsight.isPresent()) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
						"Error while sending NeoLoad Web Main statistics to Insights API: " + errorMessageInsight.get());
			}

			/**
			 * 2.5 Send NeoLoad Web Element Values to Insights API
			 */
			requestContentBuilder.append("Send NeoLoad Web Element Values to Insights API.\n");
			final Optional<String> errorMessageInsightApi = newRelicRestClient.sendNLWebElementValuesToInsightsAPI(nlWebElementValues);
			if (errorMessageInsightApi.isPresent()) {
				return newErrorResult(requestContentBuilder, context, Constants.STATUS_CODE_TECHNICAL_ERROR,
						"Error while sending NeoLoad Web Element Values to Insights API: " + errorMessageInsightApi.get());
			}

		}
		sampleResult.sampleEnd();
		sampleResult.setRequestContent(requestContentBuilder.toString());
		sampleResult.setResponseContent(responseContentBuilder.toString());
		return sampleResult;
	}

	private String getDefaultDataExchangeApiUrl(final Context context) {
		return "http://" + context.getControllerIp() + ":7400/DataExchange/v1/Service.svc/";
	}

	private static SampleResult newErrorResult(final StringBuilder requestContentBuilder, final Context context, final String statusCode, final String statusMessage) {
		final SampleResult sampleResult = ResultFactory.newErrorResult(context, statusCode, statusMessage);
		sampleResult.setRequestContent(requestContentBuilder.toString());
		return sampleResult;			
	}
	
	private static SampleResult newErrorResult(final StringBuilder requestContentBuilder, final Context context, final String statusCode, final String statusMessage, final Exception exception) {
		final SampleResult sampleResult = ResultFactory.newErrorResult(context, statusCode, statusMessage, exception);
		sampleResult.setRequestContent(requestContentBuilder.toString());
		return sampleResult;			
	}
	
	@Override
	public void stopExecute() {
		// nothing to do
	}

}
