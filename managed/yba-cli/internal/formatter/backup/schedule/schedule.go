/*
 * Copyright (c) YugaByte, Inc.
 */

package backup

import (
	"encoding/json"

	"github.com/sirupsen/logrus"
	ybaclient "github.com/yugabyte/platform-go-client"
	"github.com/yugabyte/yugabyte-db/managed/yba-cli/internal/formatter"
)

const (
	defaultScheduleListing = "table {{.Name}}\t{{.UUID}}\t{{.UniverseUUID}}" +
		"\t{{.StorageConfigurationName}}\t{{.Frequency}}\t{{.CronExpression}}\t{{.NextTaskTime}}"

	taskParamsHeader            = "Task Params"
	taskTypeHeader              = "Task Type"
	nextIncrementBackupTaskTime = "Next Increment Backup Task Time"
)

// Context for provider outputs
type Context struct {
	formatter.HeaderContext
	formatter.Context
	s ybaclient.Schedule
}

// NewSchedulesFormat for formatting output
func NewSchedulesFormat(source string) formatter.Format {
	switch source {
	case formatter.TableFormatKey, "":
		format := defaultScheduleListing
		return formatter.Format(format)
	default: // custom format or json or pretty
		return formatter.Format(source)
	}
}

// Write renders the context for a list of Schedules
func Write(ctx formatter.Context, schedules []ybaclient.Schedule) error {
	// Check if the format is JSON or Pretty JSON
	if (ctx.Format.IsJSON() || ctx.Format.IsPrettyJSON()) && ctx.Command.IsListCommand() {
		// Marshal the slice of schedules into JSON
		var output []byte
		var err error

		if ctx.Format.IsPrettyJSON() {
			output, err = json.MarshalIndent(schedules, "", "  ")
		} else {
			output, err = json.Marshal(schedules)
		}

		if err != nil {
			logrus.Errorf("Error marshaling schedules to json: %v\n", err)
			return err
		}

		// Write the JSON output to the context
		_, err = ctx.Output.Write(output)
		return err
	}
	render := func(format func(subContext formatter.SubContext) error) error {
		for _, schedule := range schedules {
			err := format(&Context{s: schedule})
			if err != nil {
				logrus.Debugf("Error rendering schedule: %v", err)
				return err
			}
		}
		return nil
	}
	return ctx.Write(NewScheduleContext(), render)
}

// NewScheduleContext creates a new context for rendering provider
func NewScheduleContext() *Context {
	scheduleCtx := Context{}
	scheduleCtx.Header = formatter.SubHeaderContext{
		"Name":                     formatter.NameHeader,
		"UUID":                     formatter.UUIDHeader,
		"UniverseName":             formatter.UniversesHeader,
		"StorageConfigurationName": formatter.StorageConfigurationHeader,
		// "Frequency":  frequencyHeader,
		// "CronExpression":    cronExpressionHeader,
		// "NextTaskTime": nextTaskTimeHeader,
	}
	return &scheduleCtx
}

// UUID fetches Schedule UUID
func (c *Context) UUID() string {
	return c.s.GetScheduleUUID()
}

// Name fetches Schedule Name
func (c *Context) Name() string {
	return c.s.GetScheduleName()
}
