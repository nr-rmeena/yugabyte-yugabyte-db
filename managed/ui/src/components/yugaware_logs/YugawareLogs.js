// Copyright (c) YugaByte, Inc.

import React, { useState } from 'react';
import { showOrRedirect } from '../../utils/LayoutUtils';
import AceEditor from 'react-ace';
import 'ace-builds/src-noconflict/ext-searchbox';
import 'ace-builds/src-noconflict/mode-java';
import 'ace-builds/src-noconflict/theme-github';
import { Col, Row } from 'react-bootstrap';
import Select from 'react-select';
import {
  YBButton,
  YBControlledNumericInputWithLabel,
  YBControlledTextInput
} from '../common/forms/fields';
import { useMount } from 'react-use';

import './YugawareLogs.scss';

const DEFAULT_MAX_LINES = 1000;

const UNIVERSE_SELECT_STYLES = {
  control: (styles) => ({
    ...styles,
    height: '42px'
  }),
  menu: (styles) => ({
    ...styles,
    zIndex: 10
  })
}

const YugawareLogs = ({ currentCustomer, yugawareLogs, getLogs, logError, fetchUniverseList }) => {
  const editorStyle = {
    width: '100%',
    height: 'calc(100vh - 150px)'
  };

  const [maxLines, setMaxLines] = useState(DEFAULT_MAX_LINES);
  const [regex, setRegex] = useState(undefined);
  const [selectedUniverse, setSelectedUniverse] = useState(undefined);
  const [universeList, setUniverseList] = useState([]);
  const [isUniverseListLoading, setIsUniverseListLoading] = useState(true);

  const doSearch = () => {
    getLogs(maxLines, regex, selectedUniverse);

    var newURL = new URL(
      window.location.protocol + '//' + window.location.host + window.location.pathname
    );
    if (regex) {
      newURL.searchParams.set('queryRegex', regex);
    }
    if (selectedUniverse) {
      newURL.searchParams.set('universeName', selectedUniverse);
    }
    if (maxLines) {
      newURL.searchParams.set('maxLines', maxLines);
    }
    window.history.replaceState('', '', newURL.toString());
  };

  useMount(() => {
    showOrRedirect(currentCustomer.data.features, 'main.logs');

    const params = new URLSearchParams(window.location.search);
    const regexFromParam = params.get('queryRegex') || undefined;
    const maxLinesFromParam = params.get('maxLines') || DEFAULT_MAX_LINES;
    const universeFromParam = params.get('universeName') || undefined;

    setRegex(regexFromParam);
    setMaxLines(maxLinesFromParam);
    setSelectedUniverse(universeFromParam);

    getLogs(maxLinesFromParam, regexFromParam, universeFromParam);
    fetchUniverseList().then((resp) => {
      const universesOptions = resp.map((uni) => {
        return {
          label: uni.name,
          value: uni.name
        }
      });
      setUniverseList(universesOptions);
      setIsUniverseListLoading(false)
    });
  });
  return (
    <div className="yugaware-logs">
      <h2 className="content-title">
        <b>YugaWare logs</b>
      </h2>
      <Row>
        <Col lg={2}>
          <div className='logs-form-label'>Universe to filter</div>
          <Select
            options={universeList}
            placeholder={isUniverseListLoading ? 'Loading...' : 'Select a universe'}
            loadingMessage={() => 'Loading...'}
            isLoading={isUniverseListLoading}
            value={selectedUniverse ? { value: selectedUniverse, label: selectedUniverse } : null}
            onChange={(val) => {
              setSelectedUniverse(val ? val.value : null)
            }}
            isClearable
            styles={UNIVERSE_SELECT_STYLES}
          />
        </Col>
        <Col lg={3}>
          <YBControlledNumericInputWithLabel
            label="Max lines to display"
            minVal={10}
            val={maxLines}
            onInputChanged={setMaxLines}
          />
        </Col>
        <Col lg={7} className="noPadding">
          <Row className="vertical-align">
            <Col lg={10}>
              <YBControlledTextInput
                val={regex}
                label="Regex"
                placeHolder="Enter your regex"
                onValueChanged={(e) => setRegex(e.target.value)}
              />
            </Col>
            <Col lg={2}>
              <YBButton
                btnText="Search"
                className="search-button"
                btnClass="btn btn-orange"
                onClick={doSearch}
              />
            </Col>
          </Row>
        </Col>
      </Row>
      <div>
        {logError ? (
          <Row>
            <Col lg={12}>Something went wrong while fetching logs.</Col>
          </Row>
        ) : (
          <Row>
            <Col lg={12}>
              <AceEditor
                theme="github"
                mode="java"
                name="dc-config-val"
                value={yugawareLogs || 'Loading...'}
                style={editorStyle}
                readOnly
                showPrintMargin={false}
                wrapEnabled={true}
              />
            </Col>
          </Row>
        )}
      </div>
    </div>
  );
};

export default YugawareLogs;
