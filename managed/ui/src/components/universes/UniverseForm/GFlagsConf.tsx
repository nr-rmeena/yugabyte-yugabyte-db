import { FC, useState } from 'react';
import { makeStyles, Box, Typography } from '@material-ui/core';
import { useTranslation } from 'react-i18next';
import { EditGFlagsConf } from './EditGFlagsConf';
import { PreviewGFlagsConf } from './PreviewGFlagsConf';
import { YBButton } from '../../../redesign/components';

interface GFlagConfProps {
  formProps: any;
  mode: string;
}

export const useStyles = makeStyles(() => ({
  buttons: {
    float: 'right'
  },
  title: {
    float: 'left'
  },
  editButton: {
    borderRadius: '8px 0px 0px 8px'
  },
  previewButton: {
    borderRadius: '0px 8px 8px 0px'
  }
}));

export const GFlagsConf: FC<GFlagConfProps> = ({ formProps, mode }) => {
  const classes = useStyles();
  const { t } = useTranslation();
  const [editClick, setEditClick] = useState<boolean>(true);
  const [previewClick, setPreviewClick] = useState<boolean>(false);

  const handleEditClick = () => {
    setEditClick(true);
    setPreviewClick(false);
  };

  const handlePreviewClick = () => {
    setEditClick(false);
    setPreviewClick(true);
  };

  return (
    <>
      <Box>
        <span className={classes.title}>
          <Typography variant="h6">{'Flag Value'}</Typography>
        </span>
        <span className={classes.buttons}>
          <YBButton
            variant="secondary"
            size="small"
            data-testid={`GFlagsConfField-EditButton`}
            onClick={handleEditClick}
            className={classes.editButton}
          >
            {t('universeForm.gFlags.edit')}
          </YBButton>
          <YBButton
            variant="secondary"
            size="small"
            onClick={handlePreviewClick}
            data-testid={`GFlagsConfField-PreviewButton`}
            className={classes.previewButton}
          >
            {t('universeForm.gFlags.preview')}
          </YBButton>
        </span>
      </Box>
      <Box mt={1}>
        {
          'Input each record as a new row, then reorder them. The sequential order of the records are significant as they are searched serially for every connection request.'
        }
      </Box>
      <Box mt={2}>
        {editClick && <EditGFlagsConf formProps={formProps} mode={mode} />}
        {previewClick && <PreviewGFlagsConf formProps={formProps} />}
      </Box>
    </>
  );
};
