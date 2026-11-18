// Package models holds the process variable shapes shared by the BPMN
// processes, the job workers and the API server.
package models

// Process / message / job type identifiers used in the BPMN models.
const (
	ProcessCapitalCall = "capital-call"
	ProcessLPResponse  = "lp-response"

	MsgLPCommitment    = "lpCommitment"    // correlationKey = callId:lpId
	MsgGPDecision      = "gpDecision"      // correlationKey = callId:lpId
	MsgForceSettlement = "forceSettlement" // correlationKey = callId
	MsgCancelCall      = "cancelCall"      // correlationKey = callId

	BridgeThresholdPercent = 10.0
	HighRiskThreshold      = 0.7
)

// LP is a Limited Partner as carried in the process (lpList element).
type LP struct {
	LPID          string  `json:"lpId"`
	CommitmentUSD float64 `json:"commitmentUSD"`
	Email         string  `json:"email"`
	DrawAmountUSD float64 `json:"drawAmountUSD,omitempty"`
}

// CapitalCallInput is the start payload of the capital-call process.
type CapitalCallInput struct {
	CallID          string  `json:"callId"`
	FundID          string  `json:"fundId"`
	TargetAmountUSD float64 `json:"targetAmountUSD"`
	LPList          []LP    `json:"lpList"`
	DeadlineDays    int     `json:"deadlineDays"`
	// SecondsPerDay compresses "business days" for demos (0 = real days).
	SecondsPerDay int `json:"secondsPerDay,omitempty"`
}

// LPResponse is the result of one lp-response child process.
type LPResponse struct {
	LPID          string  `json:"lpId"`
	Email         string  `json:"email,omitempty"`
	CommitmentUSD float64 `json:"commitmentUSD,omitempty"`
	DrawAmountUSD float64 `json:"drawAmountUSD,omitempty"`
	Status        string  `json:"status"` // committed | deferred | defaulted
	AmountUSD     float64 `json:"amountUSD"`
	RiskScore     float64 `json:"riskScore"`
	FollowUps     int     `json:"followUps,omitempty"`
}

type BridgeResult struct {
	ConfirmationID string  `json:"confirmationId"`
	DrawdownUSD    float64 `json:"drawdownUSD"`
	FeeUSD         float64 `json:"feeUSD"`
}

type PortfolioRisk struct {
	ConcentrationScore float64  `json:"concentrationScore"` // HHI 0..1
	TopRiskyLPs        []string `json:"topRiskyLPs"`
	AverageRiskScore   float64  `json:"averageRiskScore"`
	ExpectedLossUSD    float64  `json:"expectedLossUSD"`
}

type GPDecision struct {
	LPID      string  `json:"lpId"`
	RiskScore float64 `json:"riskScore"`
	Action    string  `json:"action"` // waive | enforce
	GPName    string  `json:"gpName"`
}

// CallState is the union of process variables the late-stage workers read.
type CallState struct {
	CallID          string         `json:"callId"`
	FundID          string         `json:"fundId"`
	TargetAmountUSD float64        `json:"targetAmountUSD"`
	DeadlineDays    int            `json:"deadlineDays"`
	LPList          []LP           `json:"lpList"`
	LPResponses     []LPResponse   `json:"lpResponses"`
	TotalCommitted  float64        `json:"totalCommitted"`
	GapUSD          float64        `json:"gapUSD"`
	GapPercent      float64        `json:"gapPercent"`
	BridgeTriggered bool           `json:"bridgeTriggered"`
	BridgeResult    *BridgeResult  `json:"bridgeResult"`
	PortfolioRisk   *PortfolioRisk `json:"portfolioRisk"`
	HighRiskLPs     []LPResponse   `json:"highRiskLPs"`
	GPDecisions     []GPDecision   `json:"gpDecisions"`
	SettlementMode  string         `json:"settlementMode"`
}
